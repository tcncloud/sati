package com.tcn.exile.bench;

import java.util.Arrays;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Simple, thread-safe sliding/bounded histogram that records long nanosecond samples and computes
 * percentiles. Writers are serialized via a lock; reads make a snapshot and sort — cost is O(n log
 * n) per read but this is only called at end-of-scenario.
 */
final class LatencyHistogram {
  private final long[] data;
  private int idx;
  private int size;
  private final ReentrantLock lock = new ReentrantLock();
  private long totalCount;

  LatencyHistogram(int capacity) {
    this.data = new long[capacity];
  }

  void record(long nanos) {
    lock.lock();
    try {
      data[idx] = nanos;
      idx = (idx + 1) % data.length;
      if (size < data.length) size++;
      totalCount++;
    } finally {
      lock.unlock();
    }
  }

  long count() {
    lock.lock();
    try {
      return totalCount;
    } finally {
      lock.unlock();
    }
  }

  /** Returns [p50, p95, p99, max] in nanoseconds. Zero if empty. */
  long[] percentiles() {
    lock.lock();
    long[] copy;
    int n;
    try {
      n = size;
      copy = Arrays.copyOf(data, n);
    } finally {
      lock.unlock();
    }
    if (n == 0) return new long[] {0, 0, 0, 0};
    Arrays.sort(copy);
    return new long[] {
      copy[Math.max(0, n / 2 - 1)],
      copy[Math.max(0, (int) Math.ceil(n * 0.95) - 1)],
      copy[Math.max(0, (int) Math.ceil(n * 0.99) - 1)],
      copy[n - 1]
    };
  }

  void reset() {
    lock.lock();
    try {
      idx = 0;
      size = 0;
      totalCount = 0;
      Arrays.fill(data, 0L);
    } finally {
      lock.unlock();
    }
  }
}
