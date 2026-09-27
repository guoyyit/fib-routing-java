package com.github.guoyyit.fibroutingjava.fib;

/**
 * 单次查找的控制信号：预算截止时间（nanoTime）与取消标志。
 * 查找过程在关键节点调用 {@link #checkpoint()}，超时/取消以异常终止，
 * 不产生部分结果。
 */
public final class LookupControl {

    private final long deadlineNanos;
    private final BooleanSupplier cancelSignal;

    public LookupControl(long deadlineNanos, BooleanSupplier cancelSignal) {
        this.deadlineNanos = deadlineNanos;
        this.cancelSignal = cancelSignal == null ? () -> false : cancelSignal;
    }

    public static LookupControl withBudget(long budgetNanos) {
        return new LookupControl(System.nanoTime() + Math.max(0, budgetNanos), null);
    }

    public long deadlineNanos() {
        return deadlineNanos;
    }

    public long remainingNanos() {
        return deadlineNanos - System.nanoTime();
    }

    /** 若已取消或超时则抛出对应异常；取消优先于超时判定。 */
    public void checkpoint() {
        if (cancelSignal.getAsBoolean() || Thread.currentThread().isInterrupted()) {
            throw new LookupCancelledException("lookup was cancelled before completion");
        }
        if (System.nanoTime() >= deadlineNanos) {
            throw new LookupTimeoutException("lookup exceeded its time budget");
        }
    }

    /** 可空的取消信号提供者。 */
    @FunctionalInterface
    public interface BooleanSupplier {
        boolean getAsBoolean();
    }
}
