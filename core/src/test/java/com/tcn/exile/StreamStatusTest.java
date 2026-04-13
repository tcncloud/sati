package com.tcn.exile;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class StreamStatusTest {

  @Test
  void isHealthyWhenActive() {
    var status =
        new StreamStatus(StreamStatus.Phase.ACTIVE, "c-1", Instant.now(), null, null, 3, 100, 2, 1);
    assertTrue(status.isHealthy());
  }

  @Test
  void isNotHealthyWhenReconnecting() {
    var status =
        new StreamStatus(
            StreamStatus.Phase.RECONNECTING,
            null,
            null,
            Instant.now(),
            "connection refused",
            0,
            50,
            5,
            3);
    assertFalse(status.isHealthy());
  }

  @Test
  void isNotHealthyWhenIdle() {
    var status = new StreamStatus(StreamStatus.Phase.IDLE, null, null, null, null, 0, 0, 0, 0);
    assertFalse(status.isHealthy());
  }

  @Test
  void isNotHealthyWhenClosed() {
    var status = new StreamStatus(StreamStatus.Phase.CLOSED, null, null, null, null, 0, 100, 0, 5);
    assertFalse(status.isHealthy());
  }

  @Test
  void phaseEnumValues() {
    assertEquals(6, StreamStatus.Phase.values().length);
  }
}
