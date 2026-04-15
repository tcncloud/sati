package com.tcn.exile;

/**
 * Read-only snapshot of the adaptive concurrency controller's state at a point in time.
 *
 * <p>Obtained via {@link ExileClient#adaptiveSnapshot()}. Intended for plugin diagnostics,
 * dashboards, and custom metrics export. Reading a snapshot is cheap (no locks, no allocation
 * beyond the record itself).
 *
 * <p>Latency fields are exposed in nanoseconds (the controller's native unit) with millisecond
 * convenience accessors layered on top.
 *
 * @param limit Current target in-flight work count. The WorkStream uses this to cap how many
 *     credits it will grant the server at a time.
 * @param minLimit Configured safety floor. The controller won't shed below this value.
 * @param maxLimit Configured safety ceiling. The controller won't grow above this value.
 * @param effectiveCeiling {@code min(maxLimit, min over plugin resourceLimits().hardMax)} — the
 *     true upper bound the controller targets, after plugin-declared structural caps.
 * @param jobP95Nanos Sliding-window p95 of job completion latency (nanos). Zero before any sample.
 * @param jobEmaNanos Exponentially-weighted moving average of job latency (nanos).
 * @param decayingMinNanos Controller's decaying minimum — the fastest observation recently seen,
 *     drifting upward slowly to avoid one lucky sample pinning the floor forever.
 * @param sloGradient Last computed SLO gradient: {@code clamp(SLO_NANOS / jobP95Nanos, 0.5..1.0)}.
 *     Values below 1.0 mean p95 is approaching the 500 ms job SLO.
 * @param minGradient Last computed min gradient: {@code clamp(decayingMinNanos / jobEmaNanos,
 *     0.5..1.0)}. Values below 1.0 indicate queueing buildup somewhere downstream.
 * @param resourceGradient Last computed resource gradient: min across plugin-declared resources
 *     that report {@code currentUsage}. Values below 1.0 indicate a resource approaching saturation
 *     (sheds starting at 70% utilization).
 * @param errorCount Cumulative plugin errors observed by the controller since construction.
 * @param sampleCount Number of successful job-completion samples fed to the controller.
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

  /** p95 latency in milliseconds (rounded down). */
  public long jobP95Millis() {
    return jobP95Nanos / 1_000_000L;
  }

  /** EMA latency in milliseconds (rounded down). */
  public long jobEmaMillis() {
    return jobEmaNanos / 1_000_000L;
  }

  /** Decaying min latency in milliseconds (rounded down). */
  public long decayingMinMillis() {
    return decayingMinNanos / 1_000_000L;
  }
}
