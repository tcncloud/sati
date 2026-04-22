package com.tcn.sati.infra.adaptive;

import com.tcn.sati.infra.backend.ResourceLimit;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SLO-aware gradient concurrency controller.
 *
 * <p>Given periodic job-completion latency samples (via {@link #recordJobCompletion}) and,
 * optionally, plugin-declared resource limits, computes a target in-flight work count that aims to
 * stay within a 500 ms job p95 budget while respecting structural plugin ceilings.
 *
 * <p>Combines three gradients and takes the most pessimistic:
 *
 * <ul>
 *   <li><b>SLO gradient</b>: {@code min(1, SLO / jobP95)} — absolute 500 ms budget.
 *   <li><b>Min gradient</b>: {@code min(1, fastCase / jobEMA)} — Vegas-style relative signal
 *       detecting queueing buildup.
 *   <li><b>Resource gradient</b>: derived from any declared {@link ResourceLimit} that reports
 *       {@code currentUsage}; sheds as utilization climbs past 70 %.
 * </ul>
 *
 * <p>When all gradients are 1.0 and there's headroom, the controller probes upward by roughly
 * {@code sqrt(limit)}. On a plugin error the limit is halved (multiplicative decrease) regardless
 * of latency.
 *
 * <p>Limit is clamped to {@code [minLimit, min(maxLimit, pluginCeiling)]}.
 *
 * <p>Thread-safe for concurrent writers and readers.
 */
public final class AdaptiveCapacity implements IntSupplier {

    private static final Logger log = LoggerFactory.getLogger(AdaptiveCapacity.class);

    // --- Tunables (package-private for tests) ---
    static final long SLO_NANOS = Duration.ofMillis(500).toNanos();
    static final int WINDOW = 100;
    static final int MIN_SAMPLES = 20;
    static final int RECOMPUTE_EVERY = 25;
    static final double GRADIENT_FLOOR = 0.5;
    static final double UTIL_SHED_START = 0.70;
    static final double UTIL_SHED_FULL = 1.00;
    static final double FAST_CASE_PERCENTILE = 0.05;
    static final long MIN_GRADIENT_NOISE_FLOOR_NANOS = 1_000_000L; // 1 ms

    private final int minLimit;
    private final int maxLimit;
    private final Supplier<List<ResourceLimit>> resourceSupplier;

    private volatile int limit;

    private final RingBuffer jobLatencies = new RingBuffer(WINDOW);
    private volatile double emaJobRtt;
    private final AtomicInteger jobSamples = new AtomicInteger();
    private final AtomicInteger errorCount = new AtomicInteger();

    // Last recompute snapshot — exposed as gauges.
    private volatile double lastSloGradient = 1.0;
    private volatile double lastMinGradient = 1.0;
    private volatile double lastResourceGradient = 1.0;

    /**
     * @param minLimit        safety floor for the computed limit (≥ 1)
     * @param initialLimit    starting point used until enough samples accumulate (≥ minLimit)
     * @param maxLimit        safety ceiling (≥ initialLimit)
     * @param resourceSupplier typically {@code backendClient::resourceLimits}; may return empty list
     */
    public AdaptiveCapacity(
            int minLimit,
            int initialLimit,
            int maxLimit,
            Supplier<List<ResourceLimit>> resourceSupplier) {
        if (minLimit < 1) throw new IllegalArgumentException("minLimit must be >= 1");
        if (initialLimit < minLimit) throw new IllegalArgumentException("initialLimit must be >= minLimit");
        if (maxLimit < initialLimit) throw new IllegalArgumentException("maxLimit must be >= initialLimit");
        this.minLimit = minLimit;
        this.maxLimit = maxLimit;
        this.resourceSupplier = Objects.requireNonNull(resourceSupplier, "resourceSupplier");
        this.limit = initialLimit;
    }

    /**
     * Record a job completion. Must not be called on event completions — event latency distributions
     * are uninformative for the job SLO signal.
     */
    public void recordJobCompletion(long nanos, boolean success) {
        if (!success) {
            errorCount.incrementAndGet();
            onError();
            return;
        }
        if (nanos < 0) nanos = 0;
        jobLatencies.add(nanos);
        updateEma(nanos);

        int n = jobSamples.incrementAndGet();
        if (n >= MIN_SAMPLES && n % RECOMPUTE_EVERY == 0) {
            recompute();
        }
    }

    /** Record an event completion. No-op for control; kept for API parity. */
    public void recordEventCompletion(long nanos, boolean success) {
        // Intentionally empty — event latency does not feed the controller.
    }

    private void updateEma(long sample) {
        double prev = emaJobRtt;
        emaJobRtt = (prev == 0.0) ? sample : 0.1 * sample + 0.9 * prev;
    }

    private long fastCaseNanos() {
        long p5 = jobLatencies.percentile(FAST_CASE_PERCENTILE);
        if (p5 <= 0) return 0;
        return Math.max(p5, MIN_GRADIENT_NOISE_FLOOR_NANOS);
    }

    private void onError() {
        int next = Math.max(minLimit, limit / 2);
        limit = next;
    }

    private void recompute() {
        long p95 = jobLatencies.percentile(0.95);
        long p5 = jobLatencies.percentile(FAST_CASE_PERCENTILE);
        double ema = emaJobRtt;
        if (p95 <= 0 || ema <= 0) return;

        long fastCase = Math.max(p5 > 0 ? p5 : p95, MIN_GRADIENT_NOISE_FLOOR_NANOS);

        double sloG = clamp((double) SLO_NANOS / p95, GRADIENT_FLOOR, 1.0);

        // Homogeneity guard: skip min-gradient for heterogeneous workloads.
        double homogeneity = (double) p5 / (double) p95;
        double minG;
        if (homogeneity < GRADIENT_FLOOR) {
            minG = 1.0;
        } else {
            minG = clamp((double) fastCase / ema, GRADIENT_FLOOR, 1.0);
        }

        double resG = computeResourceGradient();
        double gradient = Math.min(Math.min(sloG, minG), resG);

        int probe = (gradient >= 1.0) ? Math.max(1, (int) Math.sqrt(limit)) : 0;
        int next = (int) Math.round(limit * gradient) + probe;

        int ceiling = Math.min(maxLimit, pluginCeiling());
        int clamped = Math.max(minLimit, Math.min(ceiling, next));

        lastSloGradient = sloG;
        lastMinGradient = minG;
        lastResourceGradient = resG;
        int old = limit;
        limit = clamped;
        if (log.isDebugEnabled() && old != clamped) {
            log.debug(
                    "adaptive limit {} -> {} (p95={}ms, p5={}ms, ema={}ms, fastCase={}ms, homogeneity={}," +
                    " sloG={}, minG={}, resG={}, ceiling={})",
                    old, clamped,
                    p95 / 1_000_000, p5 / 1_000_000, (long) ema / 1_000_000, fastCase / 1_000_000,
                    String.format("%.2f", homogeneity), String.format("%.2f", sloG),
                    String.format("%.2f", minG), String.format("%.2f", resG), ceiling);
        }
    }

    private double computeResourceGradient() {
        double min = 1.0;
        for (ResourceLimit r : resourceSupplier.get()) {
            if (r.currentUsage() < 0) continue;
            double util = r.utilization();
            double g;
            if (util < UTIL_SHED_START) {
                g = 1.0;
            } else if (util >= UTIL_SHED_FULL) {
                g = GRADIENT_FLOOR;
            } else {
                g = 1.0 - (util - UTIL_SHED_START) / (UTIL_SHED_FULL - UTIL_SHED_START);
                if (g < GRADIENT_FLOOR) g = GRADIENT_FLOOR;
            }
            if (g < min) min = g;
        }
        return min;
    }

    private int pluginCeiling() {
        int min = Integer.MAX_VALUE;
        for (ResourceLimit r : resourceSupplier.get()) {
            if (r.hardMax() < min) min = r.hardMax();
        }
        return min;
    }

    private static double clamp(double v, double lo, double hi) {
        if (v < lo) return lo;
        if (v > hi) return hi;
        return v;
    }

    // --- IntSupplier + metrics accessors ---

    @Override
    public int getAsInt() { return limit; }

    public int limit() { return limit; }
    public int minLimit() { return minLimit; }
    public int maxLimit() { return maxLimit; }
    public long jobP95Nanos() { return jobLatencies.percentile(0.95); }
    public long jobEmaNanos() { return (long) emaJobRtt; }
    public long decayingMinNanos() { return fastCaseNanos(); }
    public double lastSloGradient() { return lastSloGradient; }
    public double lastMinGradient() { return lastMinGradient; }
    public double lastResourceGradient() { return lastResourceGradient; }
    public int effectiveCeiling() { return Math.min(maxLimit, pluginCeiling()); }
    public int errorCount() { return errorCount.get(); }
    public int sampleCount() { return jobSamples.get(); }
    public void resetErrorCount() { errorCount.set(0); }
}
