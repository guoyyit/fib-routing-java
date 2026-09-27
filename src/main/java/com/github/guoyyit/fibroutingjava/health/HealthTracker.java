package com.github.guoyyit.fibroutingjava.health;

import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Process-wide registry of disabled (failed or administratively down) next hops.
 *
 * <p>Health is intentionally independent of route versions: a failure must be
 * observed by in-flight lookups immediately without waiting for a new route
 * version. Reads use an immutable snapshot of the id set at one instant.
 */
@Component
public final class HealthTracker {

    private final Set<String> disabledIds = ConcurrentHashMap.newKeySet();

    public HealthTracker() {
    }

    /** Marks a hop disabled; subsequent and in-flight lookups never select it. */
    public void disable(String hopId) {
        if (hopId == null || hopId.isBlank()) {
            throw new IllegalArgumentException("hopId must not be blank");
        }
        disabledIds.add(hopId);
    }

    /** Marks a hop enabled again; a no-op when it was already healthy. */
    public void enable(String hopId) {
        if (hopId == null || hopId.isBlank()) {
            throw new IllegalArgumentException("hopId must not be blank");
        }
        disabledIds.remove(hopId);
    }

    public boolean isEnabled(String hopId) {
        return !disabledIds.contains(hopId);
    }

    /** Immutable view of the hop ids currently marked disabled. */
    public Set<String> disabledIds() {
        return Collections.unmodifiableSet(Set.copyOf(disabledIds));
    }
}
