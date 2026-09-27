package com.github.guoyyit.fibroutingjava.trie;

/**
 * Deadline budget for a single forwarding lookup.
 *
 * <p>A non-positive timeout fails fast before any trie work, which also makes
 * timeout behaviour deterministically testable. Thread interruption is treated
 * identically to a deadline: the lookup returns TIMEOUT and never a partial
 * answer.
 */
public final class LookupBudget {

    private final long deadlineNanos;
    private final boolean failFast;

    private LookupBudget(long deadlineNanos, boolean failFast) {
        this.deadlineNanos = deadlineNanos;
        this.failFast = failFast;
    }

    /** Creates a budget of the requested timeout; {@code <= 0} always expires. */
    public static LookupBudget ofTimeout(long timeoutNanos) {
        if (timeoutNanos <= 0) {
            return new LookupBudget(0L, true);
        }
        return new LookupBudget(System.nanoTime() + timeoutNanos, false);
    }

    /** True when the caller must stop and return a TIMEOUT result. */
    public boolean expired() {
        if (failFast) {
            return true;
        }
        return Thread.currentThread().isInterrupted() || System.nanoTime() - deadlineNanos >= 0;
    }
}
