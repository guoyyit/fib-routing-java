package com.github.guoyyit.fibroutingjava.health;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * 下一跳运行期健康状态平面（与配置平面分离）。
 *
 * <p>不健康集合以不可变快照保存，每次更新通过 CAS 自旋构造新 Set 并以
 * {@code volatile} 引用切换发布；查询在选路边界读取当前快照：</p>
 * <ul>
 *   <li>下一跳被标记失效后，任何新的选择都不会再选中它（立即切换）；</li>
 *   <li>一次查询内多次读取拿到的是同一个不可变 Set，不会出现字段级混合；</li>
 *   <li>从未上报状态的下一跳视为健康（仅受路由项 enabled 开关约束）。</li>
 * </ul>
 */
@Component
public class HealthRegistry {

    private volatile Set<String> unhealthy = Collections.emptySet();

    /** 标记下一跳健康状态。 */
    public void setHealthy(String nextHopId, boolean healthy) {
        if (nextHopId == null || nextHopId.isBlank()) {
            throw new IllegalArgumentException("nextHopId must not be blank");
        }
        while (true) {
            Set<String> current = unhealthy;
            boolean present = current.contains(nextHopId);
            if (healthy == !present) {
                return; // 状态未变化，无需发布
            }
            Set<String> next;
            if (healthy) {
                // ConcurrentHashMap 保证不会有 null
                next = ConcurrentHashMap.newKeySet();
                next.addAll(current);
                next.remove(nextHopId);
            } else {
                next = ConcurrentHashMap.newKeySet();
                next.addAll(current);
                next.add(nextHopId);
            }
            next = Collections.unmodifiableSet(next);
            if (UNHEALTHY.compareAndSet(this, current, next)) {
                return;
            }
        }
    }

    /** 下一跳当前是否健康；从未上报者默认为健康。 */
    public boolean isHealthy(String nextHopId) {
        return !unhealthy.contains(nextHopId);
    }

    /** 返回当前不健康下一跳集合的不可变快照。 */
    public Set<String> unhealthySnapshot() {
        return unhealthy;
    }

    private static final java.lang.invoke.VarHandle UNHEALTHY;

    static {
        try {
            UNHEALTHY = java.lang.invoke.MethodHandles.lookup().findVarHandle(
                    HealthRegistry.class, "unhealthy", Set.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }
}
