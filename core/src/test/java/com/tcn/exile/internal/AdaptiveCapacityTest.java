package com.tcn.exile.internal;

import static com.tcn.exile.internal.AdaptiveCapacity.*;
import static org.junit.jupiter.api.Assertions.*;

import com.tcn.exile.handler.ResourceLimit;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class AdaptiveCapacityTest {

  private static final long MS = 1_000_000L;

  // --- Construction ---

  @Test
  void rejectsInvalidBounds() {
    Supplier<List<ResourceLimit>> empty = List::of;
    assertThrows(IllegalArgumentException.class, () -> new AdaptiveCapacity(0, 10, 100, empty));
    assertThrows(IllegalArgumentException.class, () -> new AdaptiveCapacity(5, 4, 100, empty));
    assertThrows(IllegalArgumentException.class, () -> new AdaptiveCapacity(5, 10, 5, empty));
  }

  @Test
  void startsAtInitialLimit() {
    var a = new AdaptiveCapacity(1, 10, 100, List::of);
    assertEquals(10, a.limit());
    assertEquals(10, a.getAsInt());
  }

  // --- Threshold-driven recompute ---

  @Test
  void doesNotRecomputeBelowMinSamples() {
    var a = new AdaptiveCapacity(1, 10, 100, List::of);
    // Feed 19 fast completions (below 500 ms SLO) — under MIN_SAMPLES, no recompute should fire.
    for (int i = 0; i < MIN_SAMPLES - 1; i++) {
      a.recordJobCompletion(50 * MS, true);
    }
    assertEquals(10, a.limit(), "limit should not change below MIN_SAMPLES");
  }

  @Test
  void probesUpwardWhenHealthy() {
    var a = new AdaptiveCapacity(1, 10, 100, List::of);
    // Feed many healthy samples (50 ms — well under SLO). EMA and p95 both low → gradient ~ 1,
    // probe adds sqrt(limit) each recompute.
    feed(a, 50 * MS, 200);
    assertTrue(a.limit() > 10, "expected limit to grow beyond initial with healthy samples");
  }

  @Test
  void shedsWhenP95ExceedsSlo() {
    var a = new AdaptiveCapacity(1, 50, 200, List::of);
    // Warm up with fast samples so decayingMin is low, then push high-latency samples.
    feed(a, 50 * MS, 50);
    int warm = a.limit();
    // Now feed latencies well above SLO (1 s) to drive p95 high.
    feed(a, 1000 * MS, 200);
    assertTrue(
        a.limit() <= warm,
        () -> "expected shed when p95 >> SLO; warm=" + warm + " after=" + a.limit());
    assertTrue(a.lastSloGradient() < 1.0);
  }

  @Test
  void onErrorHalvesLimit() {
    var a = new AdaptiveCapacity(1, 20, 200, List::of);
    a.recordJobCompletion(1, false);
    assertEquals(10, a.limit());
    a.recordJobCompletion(1, false);
    assertEquals(5, a.limit());
    // Bottoms out at minLimit, not below.
    for (int i = 0; i < 10; i++) {
      a.recordJobCompletion(1, false);
    }
    assertEquals(1, a.limit());
    assertEquals(12, a.errorCount());
  }

  // --- Resource integration ---

  @Test
  void clampsToPluginCeiling() {
    var resources =
        new AtomicReference<List<ResourceLimit>>(List.of(ResourceLimit.capOnly("db_pool", 4)));
    var a = new AdaptiveCapacity(1, 50, 200, resources::get);
    feed(a, 10 * MS, 200);
    assertTrue(a.limit() <= 4, "expected limit to be clamped to hardMax=4; got " + a.limit());
  }

  @Test
  void resourceCeilingMinAcrossAll() {
    var resources =
        new AtomicReference<List<ResourceLimit>>(
            List.of(ResourceLimit.capOnly("db_pool", 8), ResourceLimit.capOnly("http_client", 4)));
    var a = new AdaptiveCapacity(1, 50, 200, resources::get);
    feed(a, 10 * MS, 200);
    assertTrue(a.limit() <= 4, "expected min-of-caps clamp; got " + a.limit());
  }

  @Test
  void resourceGradientShedsWhenUtilHigh() {
    var resources =
        new AtomicReference<List<ResourceLimit>>(
            List.of(new ResourceLimit("pool", 100, 95))); // 95% utilization
    var a = new AdaptiveCapacity(1, 50, 200, resources::get);
    feed(a, 10 * MS, 50); // healthy latency
    assertTrue(
        a.lastResourceGradient() < 1.0,
        "expected resource gradient to drop at 95% utilization; got " + a.lastResourceGradient());
  }

  @Test
  void resourceGradientInactiveBelowShedStart() {
    var resources =
        new AtomicReference<List<ResourceLimit>>(
            List.of(new ResourceLimit("pool", 100, 50))); // 50% — below 70% threshold
    var a = new AdaptiveCapacity(1, 50, 200, resources::get);
    feed(a, 10 * MS, 50);
    assertEquals(1.0, a.lastResourceGradient(), 1e-9);
  }

  @Test
  void unknownUsageDoesNotDriveResourceGradient() {
    // capOnly → currentUsage = -1, should be ignored by the gradient (but still clamp ceiling).
    var resources =
        new AtomicReference<List<ResourceLimit>>(List.of(ResourceLimit.capOnly("pool", 100)));
    var a = new AdaptiveCapacity(1, 50, 200, resources::get);
    feed(a, 10 * MS, 50);
    assertEquals(1.0, a.lastResourceGradient(), 1e-9);
  }

  // --- Metrics accessors ---

  @Test
  void metricsAccessorsReflectInternalState() {
    var a = new AdaptiveCapacity(1, 50, 200, List::of);
    feed(a, 100 * MS, 50);
    assertTrue(a.jobEmaNanos() > 0);
    assertTrue(a.decayingMinNanos() > 0);
    assertTrue(a.jobP95Nanos() > 0);
    assertEquals(1, a.minLimit());
    assertEquals(200, a.maxLimit());
    assertEquals(200, a.effectiveCeiling()); // no resource → full ceiling
  }

  @Test
  void effectiveCeilingReflectsResources() {
    var a =
        new AdaptiveCapacity(
            1,
            10,
            200,
            () -> List.of(ResourceLimit.capOnly("x", 16), ResourceLimit.capOnly("y", 8)));
    assertEquals(8, a.effectiveCeiling());
  }

  @Test
  void sloNanosMatches500Ms() {
    assertEquals(Duration.ofMillis(500).toNanos(), SLO_NANOS);
  }

  // --- Regression: min-gradient collapse on heterogeneous workloads ---
  //
  // These tests cover the bug reproduced live against finvi: a single sub-ms
  // sample pinned decayingMin at its bit-shift fixed-point, the min-gradient
  // permanently floored at 0.5, and the limit halved every recompute until
  // it collapsed to minLimit. Fixed by replacing the running decaying min
  // with p5 of the ring buffer clamped up to a 1 ms noise floor.

  /** A single very-fast sample (cache hit) must not permanently pin the "fast case". */
  @Test
  void singleSubMsOutlierDoesNotPinFastCase() {
    var a = new AdaptiveCapacity(1, 50, 200, List::of);
    // One sub-ms sample followed by many normal-latency samples.
    a.recordJobCompletion(100_000L, true); // 0.1 ms (cache hit)
    feed(a, 20 * MS, 200);
    // decayingMinNanos() now returns max(p5, 1 ms). p5 of a window with 99%
    // of samples at 20 ms and 1 at 0.1 ms is 20 ms (the one outlier sits far
    // below p5). fastCase >= 1 ms from the noise floor regardless.
    assertTrue(
        a.decayingMinNanos() >= MIN_GRADIENT_NOISE_FLOOR_NANOS,
        "fast-case should be clamped above the noise floor; got "
            + a.decayingMinNanos() / 1_000_000
            + "ms");
    assertTrue(
        a.decayingMinNanos() >= 10 * MS,
        "fast-case (p5) should reflect the majority workload, not the single outlier; got "
            + a.decayingMinNanos() / 1_000_000
            + "ms");
  }

  /**
   * Heterogeneous workload: 30 % cache hits at 0.1 ms and 70 % real work at 20 ms, with plenty of
   * SLO headroom and no errors. The pre-fix controller collapsed the limit to 1 over ~100 samples
   * because min-gradient pinned at 0.5 every recompute. Post-fix the limit should stay near the
   * initial value.
   */
  @Test
  void heterogeneousWorkloadDoesNotCollapseToMinLimit() {
    var a = new AdaptiveCapacity(1, 50, 200, List::of);
    // 500 samples: 30% sub-ms (cache hits), 70% at 20ms (real DB work). Alternating so the
    // ring buffer sees both consistently.
    for (int i = 0; i < 500; i++) {
      long sample = (i % 10 < 3) ? 100_000L /* 0.1 ms */ : 20 * MS;
      a.recordJobCompletion(sample, true);
    }
    assertTrue(
        a.limit() > 1,
        "limit should not collapse to minLimit on a heterogeneous workload with SLO"
            + " headroom; got limit="
            + a.limit()
            + " (ema="
            + a.jobEmaNanos() / 1_000_000
            + "ms, p95="
            + a.jobP95Nanos() / 1_000_000
            + "ms, fastCase="
            + a.decayingMinNanos() / 1_000_000
            + "ms, minG="
            + a.lastMinGradient()
            + ")");
    // SLO comfortable (way under 500 ms), so sloGradient should be 1.0 —
    // min-gradient is the only thing that could have sheared.
    assertEquals(1.0, a.lastSloGradient(), 1e-9);
  }

  /**
   * Regression lock-in: 100 % cache-hit workload (every sample ≤ 1 ms). The noise floor should keep
   * the min-gradient near 1.0 by treating all samples as equally "fast" relative to the floor,
   * avoiding a degenerate shed on a plugin that's genuinely always fast.
   */
  @Test
  void allSubMsSamplesDoNotCollapseLimit() {
    var a = new AdaptiveCapacity(1, 50, 200, List::of);
    feed(a, 500_000L /* 0.5 ms */, 500);
    assertTrue(a.limit() > 1, "uniformly fast workload should not shed; got limit=" + a.limit());
  }

  // --- Helper ---

  private static void feed(AdaptiveCapacity a, long nanos, int count) {
    for (int i = 0; i < count; i++) {
      a.recordJobCompletion(nanos, true);
    }
  }
}
