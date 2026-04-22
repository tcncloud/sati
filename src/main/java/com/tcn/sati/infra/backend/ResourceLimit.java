package com.tcn.sati.infra.backend;

/**
 * Declares a structural resource cap that the adaptive concurrency controller uses to clamp
 * its ceiling and compute a resource utilization gradient.
 *
 * @param name         human-readable resource name (e.g. "db_pool")
 * @param hardMax      the structural upper bound (e.g. pool max connections × multiplier); must be > 0
 * @param currentUsage live usage count (active + waiting), or -1 if unknown/unavailable
 */
public record ResourceLimit(String name, int hardMax, int currentUsage) {

    public ResourceLimit {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("name must be non-blank");
        if (hardMax <= 0) throw new IllegalArgumentException("hardMax must be > 0");
        if (currentUsage < -1) throw new IllegalArgumentException("currentUsage must be -1 (unknown) or >= 0");
    }

    /** Convenience factory for cap-only declarations (no live utilization tracking). */
    public static ResourceLimit capOnly(String name, int hardMax) {
        return new ResourceLimit(name, hardMax, -1);
    }

    /**
     * Returns the utilization ratio {@code currentUsage / hardMax}, or -1 if usage is unknown.
     */
    public double utilization() {
        if (currentUsage < 0) return -1;
        return (double) currentUsage / hardMax;
    }
}
