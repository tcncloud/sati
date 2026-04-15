package com.tcn.exile;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class AdaptiveSnapshotTest {

  @Test
  void millisAccessorsRoundDownFromNanos() {
    var s =
        new AdaptiveSnapshot(
            10, 1, 100, 100, 1_500_000L, 2_900_000L, 999_999L, 1.0, 1.0, 1.0, 0, 0);
    assertEquals(1, s.jobP95Millis());
    assertEquals(2, s.jobEmaMillis());
    assertEquals(0, s.decayingMinMillis(), "sub-millisecond truncates to 0");
  }

  @Test
  void emptyStateIsWellFormed() {
    var s = new AdaptiveSnapshot(1, 1, 100, 100, 0L, 0L, 0L, 1.0, 1.0, 1.0, 0, 0);
    assertEquals(0, s.jobP95Millis());
    assertEquals(0, s.jobEmaMillis());
    assertEquals(0, s.decayingMinMillis());
    assertEquals(1.0, s.sloGradient(), 1e-9);
  }
}
