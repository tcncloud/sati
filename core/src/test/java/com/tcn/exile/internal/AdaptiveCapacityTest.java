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

  // --- Helper ---

  private static void feed(AdaptiveCapacity a, long nanos, int count) {
    for (int i = 0; i < count; i++) {
      a.recordJobCompletion(nanos, true);
    }
  }
}
