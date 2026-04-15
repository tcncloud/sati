package com.tcn.exile.internal;

import com.tcn.exile.handler.ResourceLimit;
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
 *       detecting queueing buildup. {@code fastCase} is {@code max(p5, 1 ms)} off the job-latency
 *       ring buffer — a resilient "realistic fast-case" that can't be pinned by a single
 *       sub-millisecond outlier (cache hits, empty lists, fast-fails) the way a true decaying
 *       minimum could.
 *   <li><b>Resource gradient</b>: derived from any declared {@link ResourceLimit} that reports
 *       {@code currentUsage}; sheds as utilization climbs past 70 %.
 * </ul>
 *
 * <p>When all gradients are 1.0 and there's headroom, the controller probes upward by roughly
 * {@code sqrt(limit)}. On a plugin error the limit is halved (multiplicative decrease) regardless
 * of latency — an error is effectively infinite latency.
 *
 * <p>Limit is clamped to {@code [minLimit, min(maxLimit, pluginCeiling)]} where {@code
 * pluginCeiling} is the minimum {@code hardMax} across all declared resources (conservative: we
 * assume any job may need any resource).
 *
 * <p>Thread-safe for concurrent writers (recordJobCompletion on virtual threads) and readers
 * (getAsInt from the WorkStream refill path, metrics callbacks).
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

  /**
   * "Fast-case" reference point for the min-gradient, as a percentile of the recent job latency
   * ring buffer. p5 resists single-outlier pinning (you need ≥ 5 % of the window to be at that
   * speed for p5 to move there) while still tracking the genuine fast-case of a diverse workload.
   */
  static final double FAST_CASE_PERCENTILE = 0.05;

  /**
   * Minimum "fast-case" latency the min-gradient will consider. Sub-millisecond job handlers (cache
   * hits, empty results) are not representative of the plugin's real throughput capacity, so
   * treating them as the fast-case causes the min-gradient to permanently floor at its 0.5 cap —
   * the controller then halves the limit every recompute until it collapses to {@code minLimit}.
   * Clamping the fast-case up to this floor eliminates that degeneracy.
   */
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
   * @param minLimit safety floor for the computed limit (≥ 1)
   * @param initialLimit starting point used until enough samples accumulate (≥ minLimit)
   * @param maxLimit safety ceiling (≥ initialLimit)
   * @param resourceSupplier typically {@code plugin::resourceLimits}; may return empty list
   */
  public AdaptiveCapacity(
      int minLimit,
      int initialLimit,
      int maxLimit,
      Supplier<List<ResourceLimit>> resourceSupplier) {
    if (minLimit < 1) {
      throw new IllegalArgumentException("minLimit must be >= 1");
    }
    if (initialLimit < minLimit) {
      throw new IllegalArgumentException("initialLimit must be >= minLimit");
    }
    if (maxLimit < initialLimit) {
      throw new IllegalArgumentException("maxLimit must be >= initialLimit");
    }
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

  /** Record an event completion. No-op for control; kept for parity with the API shape. */
  public void recordEventCompletion(long nanos, boolean success) {
    // Intentionally empty — event latency does not feed the controller.
  }

  private void updateEma(long sample) {
    double prev = emaJobRtt;
    emaJobRtt = (prev == 0.0) ? sample : 0.1 * sample + 0.9 * prev;
  }

  /**
   * The "fast-case" job latency used by the min-gradient: the 5th-percentile of the recent ring
   * buffer, clamped up to {@link #MIN_GRADIENT_NOISE_FLOOR_NANOS}. Single sub-millisecond outliers
   * can't pin this the way a true running minimum can. Returns {@code 0} before the ring buffer has
   * any samples — callers must handle that case.
   */
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
    if (p95 <= 0 || ema <= 0) {
      return;
    }
    long fastCase = Math.max(p5 > 0 ? p5 : p95, MIN_GRADIENT_NOISE_FLOOR_NANOS);

    double sloG = clamp((double) SLO_NANOS / p95, GRADIENT_FLOOR, 1.0);

    // Homogeneity guard: min-gradient is the Vegas-style "queueing detector" — it assumes
    // the fast-case latency is a meaningful reference for the EMA. That assumption holds for
    // homogeneous workloads (every call does roughly the same work), but not for plugins that
    // mix sub-ms cache hits with tens-of-ms JDBC calls: the ratio then reflects workload
    // variance rather than queueing. We detect that by comparing p5 to p95 — when p5/p95 is
    // below the gradient floor (i.e. the ratio would peg at 0.5 regardless of EMA) we skip
    // the signal and let the SLO/resource gradients drive the limit alone.
    double homogeneity = (double) p5 / (double) p95;
    double minG;
    if (homogeneity < GRADIENT_FLOOR) {
      minG = 1.0; // workload too heterogeneous; trust SLO + resource signals instead.
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
          "adaptive limit {} -> {} (p95={}ms, p5={}ms, ema={}ms, fastCase={}ms, homogeneity={},"
              + " sloG={}, minG={}, resG={}, ceiling={})",
          old,
          clamped,
          p95 / 1_000_000,
          p5 / 1_000_000,
          (long) ema / 1_000_000,
          fastCase / 1_000_000,
          String.format("%.2f", homogeneity),
          String.format("%.2f", sloG),
          String.format("%.2f", minG),
          String.format("%.2f", resG),
          ceiling);
    }
  }

  private double computeResourceGradient() {
    double min = 1.0;
    for (ResourceLimit r : resourceSupplier.get()) {
      if (r.currentUsage() < 0) {
        continue;
      }
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
  public int getAsInt() {
    return limit;
  }

  public int limit() {
    return limit;
  }

  public int minLimit() {
    return minLimit;
  }

  public int maxLimit() {
    return maxLimit;
  }

  public long jobP95Nanos() {
    return jobLatencies.percentile(0.95);
  }

  public long jobEmaNanos() {
    return (long) emaJobRtt;
  }

  /**
   * Returns the "fast-case" latency used by the min-gradient — p5 of the recent job latencies,
   * clamped up to a 1 ms noise floor. Named {@code decayingMinNanos} for backwards compatibility
   * with {@link com.tcn.exile.AdaptiveSnapshot} (this method was originally a literal decaying
   * minimum; it was replaced with p5-plus-floor to eliminate the single-outlier pin pathology).
   */
  public long decayingMinNanos() {
    return fastCaseNanos();
  }

  public double lastSloGradient() {
    return lastSloGradient;
  }

  public double lastMinGradient() {
    return lastMinGradient;
  }

  public double lastResourceGradient() {
    return lastResourceGradient;
  }

  public int effectiveCeiling() {
    return Math.min(maxLimit, pluginCeiling());
  }

  public int errorCount() {
    return errorCount.get();
  }

  public int sampleCount() {
    return jobSamples.get();
  }

  /** Reset error counter — called periodically by wiring code (e.g., per minute). */
  public void resetErrorCount() {
    errorCount.set(0);
  }
}
