/*
 *  (C) 2017-2026 TCN Inc. All rights reserved.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package com.tcn.exile.log;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Suppresses repeated identical error logs within a rolling time window.
 *
 * <p>Use when a failure mode is expected to repeat at high volume (e.g. a per-call gRPC error that
 * fires for every event of a class). Without rate limiting, the resulting stack-trace storm
 * dominates the log file and obscures novel problems.
 *
 * <p>Semantics: each {@code key} gets its own window. The first call per window returns {@code
 * true} (caller logs in full, including any throwable). Subsequent calls within the same window
 * return {@code false} and silently increment a suppressed counter. When the next "log full" fires,
 * {@link #pollSuppressedCount(String)} reports how many were dropped so the operator sees a count.
 *
 * <p>This is best-effort: a small number of races at the window boundary can produce slightly more
 * than one log per window. That is intentional — correctness is more important than precision.
 */
public final class RateLimitedErrorLogger {
  private final long windowMs;
  private final ConcurrentHashMap<String, AtomicLong> lastLogged = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, AtomicLong> suppressed = new ConcurrentHashMap<>();

  /** Creates a rate limiter with the given window in milliseconds (e.g. 60_000 for once/minute). */
  public RateLimitedErrorLogger(long windowMs) {
    if (windowMs <= 0) {
      throw new IllegalArgumentException("windowMs must be positive");
    }
    this.windowMs = windowMs;
  }

  /**
   * @return true if the caller should log this occurrence in full; false if it should be silently
   *     suppressed (and a counter incremented so the next "log full" can report the count).
   */
  public boolean shouldLogFull(String key) {
    long now = System.currentTimeMillis();
    AtomicLong last = lastLogged.computeIfAbsent(key, k -> new AtomicLong(0L));
    long previous = last.get();
    if (now - previous >= windowMs && last.compareAndSet(previous, now)) {
      return true;
    }
    suppressed.computeIfAbsent(key, k -> new AtomicLong(0L)).incrementAndGet();
    return false;
  }

  /**
   * @return the number of occurrences suppressed for this key since the last call, and resets the
   *     counter.
   */
  public long pollSuppressedCount(String key) {
    AtomicLong counter = suppressed.get(key);
    return counter == null ? 0L : counter.getAndSet(0L);
  }
}
