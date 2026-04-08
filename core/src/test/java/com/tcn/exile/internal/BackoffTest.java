package com.tcn.exile.internal;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class BackoffTest {

  @Test
  void firstAttemptReturnsZero() {
    var b = new Backoff();
    assertEquals(0, b.nextDelayMs());
  }

  @Test
  void delayIncreasesExponentially() {
    var b = new Backoff();
    b.recordFailure();
    long d1 = b.nextDelayMs();
    assertTrue(d1 >= 1600 && d1 <= 2400, "First failure ~2s, got " + d1);

    b.recordFailure();
    long d2 = b.nextDelayMs();
    assertTrue(d2 >= 3200 && d2 <= 4800, "Second failure ~4s, got " + d2);

    b.recordFailure();
    long d3 = b.nextDelayMs();
    assertTrue(d3 >= 6400 && d3 <= 9600, "Third failure ~8s, got " + d3);
  }

  @Test
  void delayCapsAtMax() {
    var b = new Backoff();
    for (int i = 0; i < 20; i++) b.recordFailure();
    long d = b.nextDelayMs();
    assertTrue(d <= 30_000, "Should cap at 30s, got " + d);
  }

  @Test
  void resetResetsToZero() {
    var b = new Backoff();
    b.recordFailure();
    b.recordFailure();
    b.reset();
    assertEquals(0, b.nextDelayMs());
  }

  @Test
  void sleepDoesNotSleepOnFirstAttempt() throws InterruptedException {
    var b = new Backoff();
    long start = System.currentTimeMillis();
    b.sleep();
    long elapsed = System.currentTimeMillis() - start;
    assertTrue(elapsed < 100, "Should not sleep on first attempt, took " + elapsed);
  }
}
