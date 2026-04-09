package com.tcn.exile.internal;

import java.util.concurrent.ThreadLocalRandom;

/** Exponential backoff with jitter for reconnection. */
public final class Backoff {

  private static final long BASE_MS = 500;
  private static final long MAX_MS = 10_000;
  private static final double JITTER = 0.2;

  private int failures;

  public Backoff() {
    this.failures = 0;
  }

  public void reset() {
    failures = 0;
  }

  public void recordFailure() {
    failures++;
  }

  /** Compute the next backoff delay in milliseconds. Returns 0 on first attempt. */
  public long nextDelayMs() {
    if (failures <= 0) return 0;
    long delay = BASE_MS * (1L << Math.min(failures - 1, 10));
    double jitter = 1.0 + (ThreadLocalRandom.current().nextDouble() * 2 - 1) * JITTER;
    return Math.min((long) (delay * jitter), MAX_MS);
  }

  /** Sleep for the computed backoff delay. */
  public void sleep() throws InterruptedException {
    long ms = nextDelayMs();
    if (ms > 0) {
      Thread.sleep(ms);
    }
  }
}
