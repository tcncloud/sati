package com.tcn.exile.internal;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;

class RingBufferTest {

  @Test
  void emptyPercentileReturnsZero() {
    var rb = new RingBuffer(10);
    assertEquals(0, rb.percentile(0.5));
    assertEquals(0, rb.percentile(0.95));
    assertEquals(0, rb.size());
  }

  @Test
  void sizeGrowsToCapacity() {
    var rb = new RingBuffer(3);
    rb.add(1);
    assertEquals(1, rb.size());
    rb.add(2);
    rb.add(3);
    assertEquals(3, rb.size());
    rb.add(4); // overwrites 1
    assertEquals(3, rb.size());
  }

  @Test
  void percentileNearestRank() {
    var rb = new RingBuffer(100);
    for (int i = 1; i <= 100; i++) {
      rb.add(i);
    }
    // Nearest-rank: ceil(p * n) with n=100 → p=0.5 → rank 50 (0-indexed 49) → 50
    assertEquals(50, rb.percentile(0.5));
    assertEquals(95, rb.percentile(0.95));
    assertEquals(99, rb.percentile(0.99));
    assertEquals(100, rb.percentile(1.0));
  }

  @Test
  void percentileClamped() {
    var rb = new RingBuffer(10);
    rb.add(5);
    assertEquals(5, rb.percentile(0.0)); // clamped up
    assertEquals(5, rb.percentile(-1)); // clamped up
    assertEquals(5, rb.percentile(1.5)); // clamped down
  }

  @Test
  void ringOverwritesOldestWhenFull() {
    var rb = new RingBuffer(3);
    rb.add(100);
    rb.add(200);
    rb.add(300);
    rb.add(400); // overwrites 100
    // Buffer now contains {200, 300, 400}
    assertEquals(200, rb.percentile(0.01));
    assertEquals(400, rb.percentile(1.0));
  }

  @Test
  void capacityMustBePositive() {
    assertThrows(IllegalArgumentException.class, () -> new RingBuffer(0));
    assertThrows(IllegalArgumentException.class, () -> new RingBuffer(-1));
  }

  @Test
  void concurrentWritesAreSafe() throws InterruptedException {
    var rb = new RingBuffer(1000);
    int writers = 8;
    int writesEach = 500;
    var start = new CountDownLatch(1);
    var done = new CountDownLatch(writers);
    for (int w = 0; w < writers; w++) {
      Thread.ofVirtual()
          .start(
              () -> {
                try {
                  start.await();
                  for (int i = 0; i < writesEach; i++) {
                    rb.add(i);
                  }
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                } finally {
                  done.countDown();
                }
              });
    }
    start.countDown();
    assertTrue(done.await(5, java.util.concurrent.TimeUnit.SECONDS));
    assertEquals(1000, rb.size()); // capped at capacity
    // Percentile doesn't throw and returns something in the valid range
    long p95 = rb.percentile(0.95);
    assertTrue(p95 >= 0 && p95 < writesEach);
  }
}
