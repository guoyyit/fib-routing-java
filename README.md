# fib-routing-java：本地网络转发表（FIB）服务

一个基于 Spring Boot 3 / Java 17 的**进程内**本地网络转发表服务，支持 IPv4/IPv6
最长前缀匹配（LPM）、加权/等价多路径确定性选路、**原子版本切换**的在线更新、
下一跳失效立即切换、路由聚合与配置冲突检测，以及已提交版本的崩溃安全持久化恢复。
查找使用进程内构造的目的地址，不依赖真实网络或外部服务。

---

## 1. 总体模型

| 概念 | 说明 |
| --- | --- |
| `IpPrefix` | 不可变 CIDR，IPv4（≤/32）/IPv6（≤/128），构造时主机位清零；拒绝 IPv4-mapped IPv6 写法 |
| `RouteEntry` | 不可变路由：`prefix + adminDistance + nextHops[]` |
| `NextHop` | 不可变下一跳：`id / address / weight / enabled` |
| `FibSnapshot` | **不可变**转发表版本；一旦发布永不修改 |
| `FibService` | 门面：暂存、校验、提交、聚合、查找；当前版本是一个 `volatile FibSnapshot` 引用 |
| `HealthRegistry` | 下一跳运行期健康平面（与配置平面分离），不可变快照 + CAS 发布 |
| `BinaryPrefixTrie` | 按位下沉的二进制前缀树，IPv4/IPv6 共用，LPM 复杂度 O(地址位宽) |
| `FibPersistenceService` | 已提交版本持久化，临时文件 + fsync + 原子 rename，CRC32 校验 |

---

## 2. 匹配语义（最长前缀 + 约束满足）

1. 目的地址沿对应协议族的前缀树逐位下沉，路径上所有命中前缀按**前缀长度从长到短**排列；
2. 同一前缀可存在多条不同**管理距离（adminDistance）**的路由，按距离从小到大排列；
3. 查找按「前缀长度降序、管理距离升序」依次尝试候选：
   - 取候选中**当前可用**的下一跳（`enabled=true` 且健康平面标记健康）；
   - 第一个能选出可用下一跳的候选胜出；
   - 若某前缀的首选路由下一跳全部不可用，会继续尝试**同前缀次优管理距离**，
     再退化到更短前缀 —— 即“**最长且满足约束**”；
4. 全部候选无可用下一跳时：启用默认路由则返回 `DEFAULT_ROUTE`，否则返回 `UNREACHABLE`。

查找结果 `LookupResult` 携带 `outcome / matchedPrefix / adminDistance / selectedNextHop / flowKey / version`。

边界示例：`/1` 以首位划界；`/31`、奇数长度 IPv6（如 `/33`）均按位精确匹配；
默认路由 `/0` 是最终兜底，不参与下一跳解析环路。

---

## 3. 选路语义（确定性加权多路径）

`WeightedRendezvousSelector` 使用**加权 Rendezvous Hashing（HRW）**：

```
score(h, flowKey) = -log(r(flowKey, h.id)) / weight(h)   // 取 score 最小者
```

- `r ∈ (0,1)` 由 `(flowKey, nextHopId)` 经 FNV-1a 折叠 + SplitMix64 雪崩确定性派生；
- **等权**候选被选概率相等 → ECMP；
- **加权**时被选比例严格正比于权重（分布为加权指数分布）；
- 同一 `(候选集合, flowKey)` 永远得到同一结果 → 确定性、跨重启可复现，与候选输入顺序无关；
- 得分完全相同时按 `nextHopId` 字典序稳定打破平局；
- **禁用（enabled=false）与失效（健康平面 unhealthy）下一跳不参与选择**；
- 某下一跳失效时，只有原本映射到它的流迁移，其他流不动 → **切换不产生重复流量**。

调用方应始终传入有意义的 `flowKey`（如五元组哈希）；不同流在多路径间分担，
同一条流稳定钉选同一路径。

---

## 4. 原子版本切换与在线更新

- 当前版本 = 一个 `volatile FibSnapshot` 引用；快照内的两棵 LPM 树与路由清单构建完成后不再修改。
- 更新分两类 API：
  - **暂存事务**：`stageUpsert / stageDelete`（仅内存）→ `commit()` 一次原子提交，或 `discardStaged()` 丢弃；
  - **直接提交**：`upsertAndCommit / deleteAndCommit / aggregateAndCommit`，不触碰共享暂存区，可多线程并发调用。
- `commit` 流程（在 `synchronized` 内）：
  1. 对「基线版本 + 本批次增删」做全部校验（字段、重复、容量、遮蔽、解析环路）；
  2. 校验通过才基于生效全集构建新的两棵树与新快照；
  3. 先把新快照持久化（原子文件替换）；
  4. **单次 volatile 写**发布新版本 —— 唯一的原子切换点。
- 查询开始时读取一次快照引用并绑定整次查找：进行中的查询只能看到某一个完整版本，
  **绝不会看到半套路由或字段级混合状态**；版本号线性递增，结果携带产生它的版本号。
- 任何校验/持久化失败都不会改变当前版本（全部成功或全部不生效）。

### 下一跳健康切换（不产生新版本路由）

健康状态由 `HealthRegistry` 独立维护（不可变 `Set` + CAS 发布）：
- 标记失效后，任何新的选择都不会再选中该下一跳（**立即切换**）；
- 一次查找读取一次健康快照在内部复用，返回前再实时复检；
  若在两步之间下一跳失效，则放弃结果并重选，**绝不返回已失效路径**；
- 全部下一跳失效时按第 2 节规则退化到次优距离/更短前缀/默认路由/不可达。

---

## 5. 路由聚合与冲突检测

`POST /fib/aggregate`：给出成员前缀，服务端计算覆盖成员区间的**最短单个 CIDR**
（非相邻成员会对齐到更短的覆盖块，例如成员 `10.0.0.0/24` 与 `10.0.3.0/24` → 覆盖域 `/22`），
撤回成员（任意管理距离）并原子提交汇总路由。

提交校验（原因码可区分）：

| Reason | 触发条件 |
| --- | --- |
| `INVALID_PREFIX / INVALID_ADDRESS` | CIDR 或下一跳地址非法、跨族、IPv4-mapped |
| `INVALID_WEIGHT / INVALID_DISTANCE` | 权重 ≤ 0、管理距离 < 0 |
| `EMPTY_NEXTHOP_SET / DUPLICATE_NEXTHOP` | 下一跳为空或 id 重复 |
| `DUPLICATE_ROUTE` | 同一显式批次出现重复 `(前缀, 距离)` |
| `TABLE_FULL` | 生效路由数超过 `fib.max-routes` |
| `SHADOWED_BY_EXISTING` | 聚合覆盖域内仍有未作为成员撤回的更具体前缀（会被静默遮蔽） |
| `AGGREGATE_MISMATCH` | 成员为空、IPv4/IPv6 混合聚合等 |
| `RESOLUTION_LOOP` | 下一跳解析图出现环（见下） |

**下一跳解析环路检测**：把每个路由视为「前缀 → 其下一跳地址按 LPM 解析到的前缀」的有向边，
在完整生效集合上做三色 DFS；存在环即拒绝并给出环上的前缀序列。默认路由 `/0` 不参与解析。
环路检测用前缀树加速，整体 O(路由数×地址位宽)，可支撑大规模提交。

普通提交允许长短前缀父子共存（LPM 下更具体优先），这是合法层次而非冲突。

---

## 6. 持久化与恢复

- **只持久化已提交版本**：暂存更新只存在于内存，从不写盘，重启即消失（未提交不生效）。
- 文件为单个 JSON + `#crc32:` 尾行；写入流程：同目录临时文件 → `fsync` → 原子 `rename`。
  崩溃窗口只可能在 rename 前（旧文件完好）或 rename 后（新文件完整），
  **不存在半个新文件覆盖旧文件**。
- 启动恢复：校验结构与 CRC；任何截断/损坏/部分写入/校验失败都判定文件不可读，
  拒绝加载并以空表启动（显式告警），**损坏内容绝不污染已提交状态**。

---

## 7. 配置语义（`application.properties`，前缀 `fib`）

| 键 | 默认值 | 语义 |
| --- | --- | --- |
| `fib.max-routes` | `1000000` | 最大路由条数（按 前缀×管理距离 计），超限提交以 `TABLE_FULL` 拒绝 |
| `fib.lookup-timeout-nanos` | `1000000000` | 单次查找时间预算；超时抛 `LOOKUP_TIMEOUT`，**不返回部分结果** |
| `fib.default-route-enabled` | `false` | 未命中前缀时是否使用默认路由；关闭则返回 `UNREACHABLE` |
| `fib.default-route-next-hop-id` / `-address` | — | 默认路由下一跳；启用默认路由时必须配置 |
| `fib.persistence-file` | `data/fib-committed.json` | 已提交版本持久化文件路径 |
| `fib.restore-on-start` | `true` | 启动是否恢复；文件损坏时拒绝加载、空表启动 |

查找也接受显式 `LookupControl(deadline, cancelSignal)`：在关键节点做检查点，
超时或取消（含线程中断）一律以异常终止，调用方**要么拿到完整结果，要么拿不到结果**。

---

## 8. HTTP API

| 方法/路径 | 说明 |
| --- | --- |
| `GET /fib/lookup?destination=&flowKey=&timeoutNanos=` | LPM + 多路径查找（目的地址进程内构造） |
| `POST /fib/routes/batch` | `{upsert:[Route], delete:[cidr@distance], autocommit}`；`autocommit=false` 仅暂存 |
| `POST /fib/commit` / `POST /fib/discard` | 原子提交 / 丢弃暂存区 |
| `POST /fib/aggregate` | `{members:[cidr], adminDistance, nextHops}`，返回计算出的汇总前缀 |
| `POST /fib/health` | `{nextHopId, healthy}`，下一跳健康状态变更，立即影响选路 |

错误统一为 `{reason, message}`：`409` 路由变更被拒（携带第 5 节原因码）、
`408 LOOKUP_TIMEOUT`、`409 LOOKUP_CANCELLED`、`400 INVALID_REQUEST`。

快速体验：

```bash
curl -s 'localhost:8080/fib/lookup?destination=10.1.2.3&flowKey=flow-1'
curl -s -XPOST localhost:8080/fib/routes/batch -H'Content-Type: application/json' -d '{
  "upsert":[{"prefix":"10.0.0.0/8","adminDistance":0,
    "nextHops":[{"id":"h1","address":"192.0.2.1","weight":1,"enabled":true}]}],
  "autocommit":true}'
curl -s -XPOST localhost:8080/fib/health -H'Content-Type: application/json' \
  -d '{"nextHopId":"h1","healthy":false}'
```

---

## 9. 构建与测试

```bash
mvn test          # 全量自动化测试
mvn spring-boot:run
```

测试覆盖（54 个用例）：

- `IpPrefixTest`：IPv4/IPv6 解析、掩码清零、奇位边界（/1、/31、/33）、非法与 v4-mapped 拒绝；
- `BinaryPrefixTrieTest`：LPM 最长优先序列、删除、v4/v6 位边界；
- `WeightedRendezvousSelectorTest`：确定性可复现、ECMP 均匀分布、权重比例、
  下一跳移除只迁移其自身映射的流（无重复流量）；
- `FibServiceLookupTest`：最长前缀胜出、管理距离优先、约束不满足时退化、
  默认路由/不可达、IPv6、禁用下一跳、结果携带观察版本；
- `RouteValidationTest`：各类字段拒绝原因、解析环路、聚合遮蔽与成功汇总、容量上限；
- `FailoverAndConcurrencyTest`：失效立即切换、恢复、全部失效不可达、
  8×50 并发写版本线性、并发读只观察完整版本；
- `PersistenceRecoveryTest`：已提交重启恢复、暂存不恢复、截断/损坏/缺 CRC 拒绝、原子写无临时文件残留；
- `LookupTimeoutCancelTest`：超时/取消/线程中断均异常终止且无部分结果；
- `LargeScalePerformanceTest`：5 万条批量一次原子提交、20 万次查找的稳态延迟
  （p50/p99/p999 受控，仅极个别操作系统/GC 抖动离群）；
- `FibWebIntegrationTest`：REST 全链路（增删改、暂存不可见、健康切换、非法拒绝 409、聚合、IPv6、超时 408）。
