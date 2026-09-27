package com.github.guoyyit.fibroutingjava.engine;

import com.github.guoyyit.fibroutingjava.model.IpPrefix;
import com.github.guoyyit.fibroutingjava.model.RouteEntry;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Staging area for a single atomic change-set.
 *
 * <p>The transaction is private to the writer and never visible to lookups.
 * Staged operations compose like a map: a later upsert replaces an earlier one
 * for the same {@code (prefix, source)} key; {@link #delete} removes one source
 * and {@link #deletePrefix} marks every source of a prefix for removal.
 */
public final class FibTransaction {

    /** Identity of a route within the table: its prefix and source. */
    public record RouteKey(IpPrefix prefix, String source) {
    }

    private final Map<RouteKey, RouteEntry> upserts = new LinkedHashMap<>();
    private final Set<RouteKey> removedKeys = new HashSet<>();
    private final Set<IpPrefix> removedPrefixes = new HashSet<>();

    private FibTransaction() {
    }

    public static FibTransaction begin() {
        return new FibTransaction();
    }

    public void upsert(RouteEntry entry) {
        RouteKey key = new RouteKey(entry.prefix(), entry.source());
        removedKeys.remove(key);
        upserts.put(key, entry);
    }

    public void delete(IpPrefix prefix, String source) {
        RouteKey key = new RouteKey(prefix, source);
        upserts.remove(key);
        removedKeys.add(key);
    }

    public void deletePrefix(IpPrefix prefix) {
        upserts.keySet().removeIf(k -> k.prefix().equals(prefix));
        removedKeys.removeIf(k -> k.prefix().equals(prefix));
        removedPrefixes.add(prefix);
    }

    public boolean isEmpty() {
        return upserts.isEmpty() && removedKeys.isEmpty() && removedPrefixes.isEmpty();
    }

    public Map<RouteKey, RouteEntry> upserts() {
        return Collections.unmodifiableMap(upserts);
    }

    public Set<RouteKey> removedKeys() {
        return Collections.unmodifiableSet(removedKeys);
    }

    public Set<IpPrefix> removedPrefixes() {
        return Collections.unmodifiableSet(removedPrefixes);
    }
}
