package com.github.guoyyit.fibroutingjava.config;

import com.github.guoyyit.fibroutingjava.model.NextHop;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 转发表配置语义：
 * <ul>
 *   <li>max-routes：最大路由条数（按前缀×管理距离计），超限变更被拒绝；</li>
 *   <li>lookup-timeout-nanos：单次查找时间预算，超时以异常终止，无部分结果；</li>
 *   <li>default-route-*：未命中任何前缀时使用的可配置默认路由；</li>
 *   <li>persistence-file：已提交版本持久化文件路径；</li>
 *   <li>restore-on-start：启动时是否从持久化文件恢复。</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "fib")
public class FibProperties {

    private int maxRoutes = 1_000_000;
    private long lookupTimeoutNanos = 1_000_000_000L;
    private String defaultRouteNextHopId;
    private String defaultRouteNextHopAddress;
    private boolean defaultRouteEnabled;
    private String persistenceFile = "data/fib-committed.json";
    private boolean restoreOnStart = true;

    public int getMaxRoutes() {
        return maxRoutes;
    }

    public void setMaxRoutes(int maxRoutes) {
        this.maxRoutes = maxRoutes;
    }

    public long getLookupTimeoutNanos() {
        return lookupTimeoutNanos;
    }

    public void setLookupTimeoutNanos(long lookupTimeoutNanos) {
        this.lookupTimeoutNanos = lookupTimeoutNanos;
    }

    public String getDefaultRouteNextHopId() {
        return defaultRouteNextHopId;
    }

    public void setDefaultRouteNextHopId(String defaultRouteNextHopId) {
        this.defaultRouteNextHopId = defaultRouteNextHopId;
    }

    public String getDefaultRouteNextHopAddress() {
        return defaultRouteNextHopAddress;
    }

    public void setDefaultRouteNextHopAddress(String defaultRouteNextHopAddress) {
        this.defaultRouteNextHopAddress = defaultRouteNextHopAddress;
    }

    public boolean isDefaultRouteEnabled() {
        return defaultRouteEnabled;
    }

    public void setDefaultRouteEnabled(boolean defaultRouteEnabled) {
        this.defaultRouteEnabled = defaultRouteEnabled;
    }

    public String getPersistenceFile() {
        return persistenceFile;
    }

    public void setPersistenceFile(String persistenceFile) {
        this.persistenceFile = persistenceFile;
    }

    public boolean isRestoreOnStart() {
        return restoreOnStart;
    }

    public void setRestoreOnStart(boolean restoreOnStart) {
        this.restoreOnStart = restoreOnStart;
    }

    /** 根据配置构造默认路由下一跳；未启用默认路由时返回 null。 */
    public NextHop defaultRouteNextHop() {
        if (!defaultRouteEnabled) {
            return null;
        }
        if (defaultRouteNextHopId == null || defaultRouteNextHopId.isBlank()
                || defaultRouteNextHopAddress == null || defaultRouteNextHopAddress.isBlank()) {
            throw new IllegalStateException(
                    "fib.default-route-enabled=true requires default-route-next-hop-id/address");
        }
        return new NextHop(defaultRouteNextHopId, defaultRouteNextHopAddress, 1, true);
    }
}
