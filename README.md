# fib-routing-java

一个**进程内**的本地网络转发表（FIB, Forwarding Information Base）服务。查找请求只使用进程内
构造的目的地址，不依赖真实网卡、DNS 或任何外部服务。支持 IPv4/IPv6 最长前缀匹配、下一跳
集合与权重/管理距离、事务化原子版本切换、健康失效切换、路由聚合校验、崩溃安全持久化，以及
容量与单次查找超时配置。

基于 Spring Boot 3 / Java 17。

---

## 1. 核心模型

| 概念 | 说明 |
| --- | --- |
| `IpAddress` | 不可变 IPv4(4 字节)/IPv6(16 字节) 地址，自带零依赖解析与 RFC 5952 规范化 |
| `IpPrefix` | 不可变 CIDR，构造时把主机位清零；提供地址/前缀包含关系 |
| `NextHop` | 下一跳：稳定 `id`、地址、正整数 `weight`、`enabled` 管理开关 |
| `RouteEntry` | 一条路由：前缀 + 来源 `source` + 管理距离 `adminDistance` + 下一跳集合 |
| `FibSnapshot` | **一个不可变的已提交版本**，无锁读取 |
| `FibTable` | 中央协调者：volatile 快照读取 + monitor 串行提交 |
| `FibTransaction` | 私有暂存区，提交前对查找不可见 |

### 多来源共存
同一前缀可存在多个来源（如 `connected`、`static`、`bgp`）。选路时：

1. **管理距离小者优先**（取值 1–255）；
2. 管理距离相同则按 `source` 字典序，保证结果确定可复现。

---

## 2. 最长前缀匹配（LPM）语义

- 底层是一棵**持久化二进制 radix trie**（`IpTrie`），按地址族在根上分离（v4 / v6 各一棵
  子树）。根分族很关键：`0.0.0.0/0` 与 `::/0` 都不含任何位，若共享根会互相误命中。
- 查找沿目的地址的位路径自根向下，收集沿途所有前缀；**路径上最后一个即最长匹配**。
- trie 是持久化数据结构：每次增删只复制受影响的"脊柱"并返回新根，因此一个已发布快照
  持有的根在其整个生命周期内永不变化——这是无锁读与版本隔离的基础。
- 多个前缀命中时，**优先最长**；若最长前缀当前没有任何可用下一跳，则**回退到次长前缀**，
  依次类推，再到族级默认路由，最后返回 `UNREACHABLE`。
- 未命中：配置了族级默认下一跳时返回默认转发；否则返回不可达。IPv4/IPv6 默认路由分别配置。

### 查找结果
`FORWARDED`（携带命中前缀、来源、所选下一跳、版本号）、`UNREACHABLE`、`TIMEOUT`。
`TIMEOUT` 不携带任何前缀/下一跳，**永不返回部分结果**。

---

## 3. 多路径选择（ECMP / WCMP）语义

选择器（`MultipathSelector`）采用**最高随机权重（HRW / Rendezvous Hashing）**：

- 对每个"合格"下一跳，用 64 位 FNV-1a（末尾加 splitmix64 终混以增强雪崩）对
  `(路由盐值, 下一跳 id, 权重, 目的地址)` 独立打分，再经 `-weight / ln(u)` 变换取最大者。
- 等权即等价 ECMP（实测约 50/50）；权重按比例提高命中概率（权重 1:1:2 实测约 25/25/50）。
- **确定且可复现**：相同输入（含列表顺序无关，按 id 归一）永远得到相同结果。
- **失效切换不扰动无关流量**：每个下一跳的分数不依赖其他下一跳。某个下一跳被禁用/失效后，
  只有原本映射到它的目的地址会改选；映射到存活下一跳的流量**原样保留**，因此切换期间
  不会对未受影响流量产生重复转发或重映射。

**合格集合** = 管理上 `enabled=true` 且不在健康失效集合中的下一跳；权重必须为正。
空集合时该路由不可用，触发上一节的前缀回退。

---

## 4. 原子版本切换

- 读路径：`FibTable.lookup` 只取一次 `volatile FibSnapshot` 引用，在其上完成整次查找。
  进行中的查询始终观察**某一个完整版本**，看不到半套路由或字段级混合状态。
- 写路径：同一时刻只有一个事务；提交在 `commitLock` 内串行执行，顺序为
  **投影候选 → 容量/结构/拓扑校验 → 构建新不可变快照 → 先持久化 → 最后一次引用切换**。
- 任何一步失败都整体拒绝：活动版本不变，暂存区被丢弃（不会残留半批变更）。
- 未提交（仅 staged / 已 rollback）的更新对查找不可见，也不会落盘。
- 提交成功版本号单调递增，结果中带版本号，便于核对观察到的是哪个版本。

---

## 5. 健康状态与撤回的即时生效

- 健康状态独立于路由版本（`HealthTracker`，并发安全 id 集合），失效/恢复**无需新版本**
  即被进行中与后续查询立即观察到。
- 每次查找把健康集合**采样一次**为不可变快照，因此单次决策不会混用变更前后状态。
- 下一跳失效：立即从合格集合剔除并改选；整前缀所有下一跳都失效：回退到更短前缀/默认。
- 路由撤回：随下次提交原子生效；进行中的查询继续使用旧快照，新查询使用新版本。

---

## 6. 聚合 / 汇总与非法变更检测

`RouteAggregator` 与提交逻辑对变更给出**可区分的拒绝原因**（`RouteErrorCode`）：

- `MALFORMED_PREFIX`：地址/前缀无法解析。
- `INVALID_ADMIN_DISTANCE`：管理距离越界（1–255）。
- `EMPTY_NEXT_HOPS` / `INVALID_WEIGHT` / `DUPLICATE_NEXT_HOP_ID`：下一跳集合问题。
- `PREFIX_FAMILY_CONFLICT`：下一跳地址族与前缀不一致，或混合 IPv4/IPv6 做聚合。
- `DUPLICATE_COMPONENT`：聚合组件重复。
- `OVERLAP_DETECTED`：聚合组件之间存在包含/重叠（CIDR 对齐块之间要么分离要么嵌套）。
- `LOOP_DETECTED`：
  - 自环——下一跳落在本路由前缀内；
  - 跨路由环——把"前缀 → 其下一跳最长匹配到的前缀"构成有向图，DFS 检测到有向环
    （A 的网关经 B、B 的网关又经 A，或更长的环）。正常的"经外部网关/逐级收敛链"不报错。
- `SHADOW_DETECTED`：聚合块被**与所命名组件不同的**其他已安装更明细路由完全覆盖，
  新汇总永远不会被命中，予以拒绝。汇总自己已存在的组件是合法的，不算遮蔽。
- `TABLE_LIMIT_EXCEEDED`：超过最大路由数。
- `PREFIX_NOT_FOUND` / `NO_ACTIVE_TRANSACTION`：删除或事务状态错误。

汇总前缀 = 覆盖所有组件的**最紧 CIDR**：取各组件网络地址最小值到广播地址最大值的公共前缀位。

---

## 7. 持久化与恢复

- 仅**已提交版本**落盘（`FibStore`）。写入流程：写同目录临时文件 → `ATOMIC_MOVE`
  （不支持原子 rename 的文件系统回退为同目录 move）覆盖正式文件。状态文件永远是
  "上一个完好版本"或"完整新版本"之一，不会出现撕裂混合。
- 文件为 `Envelope { version, payloadSha256, routes }`，启动时校验 SHA-256：
  校验失败或 JSON 损坏会把文件**隔离改名**（`*.corrupt.<时间戳>`）并按空表启动，
  **损坏/部分写入绝不污染已提交内存状态**。
- 重启后自动恢复最后一个已提交版本及其版本号；未提交更新不恢复。
- 持久化失败发生在引用切换之前，因此落盘失败不会改变活动版本。

---

## 8. 配置项（`application.properties`，前缀 `fib`）

| 属性 | 默认 | 语义 |
| --- | --- | --- |
| `fib.max-routes` | `100000` | 全表（跨所有前缀/来源）路由条目上限，超限提交被拒 |
| `fib.lookup-timeout-nanos` | `10000000`（10ms） | 单次查找截止；`<=0` 立即失败为 TIMEOUT；线程中断同样按 TIMEOUT 处理 |
| `fib.default-next-hop-ipv4` | 空 | IPv4 未命中默认下一跳；空则 UNREACHABLE |
| `fib.default-next-hop-ipv6` | 空 | IPv6 未命中默认下一跳；空则 UNREACHABLE |
| `fib.store-path` | `data/fib-state.json` | 已提交版本状态文件路径 |
| `fib.persistence-enabled` | `true` | 是否启用持久化；关闭后纯内存运行 |

> 单次查找也可用 `GET /api/fib/lookup?timeoutNanos=` 临时覆盖截止时间。

---

## 9. HTTP API

| 方法与路径 | 作用 |
| --- | --- |
| `GET /api/fib/lookup?destination=&timeoutNanos=` | 进程内目的地址查找 |
| `POST /api/fib/transactions` | 开启一个事务（单会话） |
| `POST /api/fib/routes` | 暂存一条路由增/改（upsert，按 前缀+来源 覆盖） |
| `DELETE /api/fib/routes?prefix=&source=` | 暂存删除：带 `source` 删单来源，不带删整个前缀 |
| `POST /api/fib/aggregate` | 校验并汇总组件，原子提交一条聚合路由 |
| `POST /api/fib/commit` | 原子提交当前事务 |
| `POST /api/fib/rollback` | 丢弃当前事务 |
| `POST /api/fib/health` | 下一跳健康变更 `{hopId, enabled}`，立即生效 |

错误响应：`400 MALFORMED_PREFIX`、`409 ILLEGAL_STATE`（如已有事务）、
`422 <具体 RouteErrorCode>`（携带 `errorCode` 与 `message`）。

### 示例
```bash
# 开事务 -> 加一条带两个加权下一跳的路由 -> 提交
curl -X POST localhost:8080/api/fib/transactions
curl -X POST localhost:8080/api/fib/routes -H 'Content-Type: application/json' -d '{
  "prefix":"10.0.0.0/8","source":"static","adminDistance":10,
  "nextHops":[{"id":"a","address":"192.0.2.1","weight":1,"enabled":true},
              {"id":"b","address":"192.0.2.2","weight":2,"enabled":true}]}'
curl -X POST localhost:8080/api/fib/commit

curl 'localhost:8080/api/fib/lookup?destination=10.4.5.6'
curl -X POST localhost:8080/api/fib/health -H 'Content-Type: application/json' \
     -d '{"hopId":"b","enabled":false}'
```

---

## 10. 性能

- 查找复杂度 O(前缀位数 + 候选前缀的来源数)，与表规模解耦；提交期的跨路由拓扑分析用候选
  trie 做 LPM，整体约 O(条目数 × 前缀深度)，不是全表两两扫描。
- 实测（10 万条 /28 路由，20 次批量提交，20 万次随机查找，单机）：
  构表约 1.5s；查找 **p50≈0.2µs，p99≈2.6µs，p999≈6µs**。
- 大规模压测默认关闭以免拖慢常规构建，按需开启：

```bash
mvn test -Dtest=LargeScalePerformanceTest#hundredThousandRoutesHaveBoundedLookupLatency \
         -Dfib.perf.run=true -Dfib.perf.strict=true
```

---

## 11. 测试覆盖

- `LongestPrefixMatchTest`：v4/v6 LPM 边界、安装顺序无关、管理距离与平局、默认路由、无可用跳回退。
- `WeightedMultipathTest`：确定性、ECMP 均匀、WCMP 成比例、失效零迁移。
- `FibTableAtomicityTest`：暂存不可见、提交原子切换、回滚、整批拒绝、更新替换、失效切换、
  全失效回退、管理禁用、删除、容量、聚合遮蔽。
- `CrossRouteLoopTest`：自环、双节点环、三节点环、合法链接受。
- `AggregationValidationTest`：v4/v6 汇总数学、重复/重叠/混族/环路拒绝原因。
- `PersistenceRecoveryTest`：重启恢复、未提交不恢复、损坏隔离、校验和、落盘失败保活、往返字段。
- `ConcurrentUpdateLookupTest`：并发提交下读者只见完整版本、健康抖动期间不返回失效跳。
- `LookupTimeoutTest`：零/负超时快速失败、遍历中超时不返回部分结果、中断处理。
- `LargeScalePerformanceTest`：万级常驻正确性/延迟 + 十万级可选压测。
- `FibControllerIntegrationTest`：HTTP 全生命周期端到端与可区分错误码。

运行：

```bash
mvn test
```
