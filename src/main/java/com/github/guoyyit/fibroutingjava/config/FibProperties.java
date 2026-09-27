package com.github.guoyyit.fibroutingjava.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Tunable FIB behaviour: capacity, lookup deadline, default routes, persistence. */
@Component
@ConfigurationProperties(prefix = "fib")
public final class FibProperties {

    private int maxRoutes = 100_000;
    private long lookupTimeoutNanos = 10_000_000L;
    private String defaultNextHopIpv4;
    private String defaultNextHopIpv6;
    private String storePath = "data/fib-state.json";
    private boolean persistenceEnabled = true;

    public int getMaxRoutes() { return maxRoutes; }
    public void setMaxRoutes(int maxRoutes) { this.maxRoutes = maxRoutes; }

    public long getLookupTimeoutNanos() { return lookupTimeoutNanos; }
    public void setLookupTimeoutNanos(long lookupTimeoutNanos) { this.lookupTimeoutNanos = lookupTimeoutNanos; }

    public String getDefaultNextHopIpv4() { return defaultNextHopIpv4; }
    public void setDefaultNextHopIpv4(String defaultNextHopIpv4) { this.defaultNextHopIpv4 = defaultNextHopIpv4; }

    public String getDefaultNextHopIpv6() { return defaultNextHopIpv6; }
    public void setDefaultNextHopIpv6(String defaultNextHopIpv6) { this.defaultNextHopIpv6 = defaultNextHopIpv6; }

    public String getStorePath() { return storePath; }
    public void setStorePath(String storePath) { this.storePath = storePath; }

    public boolean isPersistenceEnabled() { return persistenceEnabled; }
    public void setPersistenceEnabled(boolean persistenceEnabled) { this.persistenceEnabled = persistenceEnabled; }
}
