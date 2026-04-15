package com.tcn.exile.handler;

/**
 * Declares a structural limit on a resource the plugin uses to handle work items.
 *
 * <p>Reported via {@link Plugin#resourceLimits()} and consumed by the adaptive concurrency
 * controller to:
 *
 * <ul>
 *   <li>Clamp its target concurrency to the minimum {@code hardMax} across all declared resources
 *       (conservative — it assumes any job may need any resource).
 *   <li>Preemptively shed concurrency as {@code currentUsage} approaches {@code hardMax}, when
 *       {@code currentUsage} is reported (≥ 0).
 * </ul>
 *
 * @param name Human-readable resource identifier (e.g. {@code "db_pool"}, {@code "http_client"}).
 *     Used for metrics cardinality — keep it stable across reports from the same plugin.
 * @param hardMax Absolute ceiling on concurrent usage (e.g. DB pool size). Must be &gt; 0.
 * @param currentUsage Live count of active slots in use. Pass {@code -1} if unknown / not tracked.
 *     When ≥ 0, the controller computes utilization and preemptively sheds as it approaches {@code
 *     hardMax}.
 */
public record ResourceLimit(String name, int hardMax, int currentUsage) {

  public ResourceLimit {
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("name must be non-blank");
    }
    if (hardMax <= 0) {
      throw new IllegalArgumentException("hardMax must be > 0");
    }
    if (currentUsage < -1) {
      throw new IllegalArgumentException("currentUsage must be -1 (unknown) or >= 0");
    }
  }

  /** Marker factory for plugins that know the cap but don't track live usage. */
  public static ResourceLimit capOnly(String name, int hardMax) {
    return new ResourceLimit(name, hardMax, -1);
  }

  /**
   * Utilization ratio in [0, 1], or {@code -1} if {@code currentUsage} is unknown.
   *
   * <p>No clamping is applied — an implementation that over-reports {@code currentUsage &gt;
   * hardMax} will return a value &gt; 1. The controller can decide how to handle that.
   */
  public double utilization() {
    if (currentUsage < 0) {
      return -1;
    }
    return (double) currentUsage / hardMax;
  }
}
