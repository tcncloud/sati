package com.tcn.exile.handler;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ResourceLimitTest {

  @Test
  void constructsWithValidArgs() {
    var r = new ResourceLimit("db_pool", 4, 2);
    assertEquals("db_pool", r.name());
    assertEquals(4, r.hardMax());
    assertEquals(2, r.currentUsage());
  }

  @Test
  void rejectsNullName() {
    assertThrows(IllegalArgumentException.class, () -> new ResourceLimit(null, 4, 0));
  }

  @Test
  void rejectsBlankName() {
    assertThrows(IllegalArgumentException.class, () -> new ResourceLimit("", 4, 0));
    assertThrows(IllegalArgumentException.class, () -> new ResourceLimit("   ", 4, 0));
  }

  @Test
  void rejectsZeroOrNegativeHardMax() {
    assertThrows(IllegalArgumentException.class, () -> new ResourceLimit("x", 0, 0));
    assertThrows(IllegalArgumentException.class, () -> new ResourceLimit("x", -1, 0));
  }

  @Test
  void rejectsCurrentUsageBelowMinusOne() {
    assertThrows(IllegalArgumentException.class, () -> new ResourceLimit("x", 4, -2));
  }

  @Test
  void allowsMinusOneForUnknownUsage() {
    var r = new ResourceLimit("x", 4, -1);
    assertEquals(-1, r.currentUsage());
  }

  @Test
  void capOnlyFactory() {
    var r = ResourceLimit.capOnly("http_client", 16);
    assertEquals("http_client", r.name());
    assertEquals(16, r.hardMax());
    assertEquals(-1, r.currentUsage());
  }

  @Test
  void utilizationWhenKnown() {
    assertEquals(0.0, new ResourceLimit("x", 4, 0).utilization(), 1e-9);
    assertEquals(0.5, new ResourceLimit("x", 4, 2).utilization(), 1e-9);
    assertEquals(1.0, new ResourceLimit("x", 4, 4).utilization(), 1e-9);
  }

  @Test
  void utilizationReturnsMinusOneWhenUnknown() {
    assertEquals(-1, new ResourceLimit("x", 4, -1).utilization(), 1e-9);
  }

  @Test
  void utilizationNotClampedAboveOne() {
    // Implementation intentionally does not clamp — over-reported usage is the caller's bug.
    var r = new ResourceLimit("x", 4, 8);
    assertEquals(2.0, r.utilization(), 1e-9);
  }
}
