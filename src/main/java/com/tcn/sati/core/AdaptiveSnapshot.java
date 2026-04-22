package com.tcn.sati.core;

/**
 * Read-only snapshot of the adaptive concurrency controller state, suitable for
 * diagnostics, dashboard display, and OTel metric callbacks.
 */
public record AdaptiveSnapshot(
        int limit,
        int minLimit,
        int maxLimit,
        int effectiveCeiling,
        long jobP95Nanos,
        long jobEmaNanos,
        long decayingMinNanos,
        double sloGradient,
        double minGradient,
        double resourceGradient,
        int errorCount,
        int sampleCount) {

    public long jobP95Millis() {
        return jobP95Nanos / 1_000_000L;
    }

    public long jobEmaMillis() {
        return jobEmaNanos / 1_000_000L;
    }

    public long decayingMinMillis() {
        return decayingMinNanos / 1_000_000L;
    }
}
