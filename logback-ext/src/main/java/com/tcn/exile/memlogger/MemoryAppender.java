/*
 *  (C) 2017-2025 TCN Inc. All rights reserved.
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
package com.tcn.exile.memlogger;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.OutputStreamAppender;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class MemoryAppender extends OutputStreamAppender<ILoggingEvent> {
  private static final int MAX_SIZE = 1000;
  private static final long MAX_EVENT_AGE_MS = 3600000; // 1 hour
  private final BlockingQueue<LogEvent> events;
  private volatile LogShipper shipper = null;
  private final AtomicBoolean isStarted = new AtomicBoolean(false);
  private Thread cleanupThread;
  private Thread shipperThread;

  public MemoryAppender() {
    this.events = new ArrayBlockingQueue<>(MAX_SIZE);
  }

  @Override
  public void setOutputStream(OutputStream outputStream) {
    super.setOutputStream(outputStream);
  }

  @Override
  public void start() {
    if (isStarted.compareAndSet(false, true)) {
      OutputStream targetStream = new MemoryOutputStream();
      setOutputStream(targetStream);
      super.start();
      MemoryAppenderInstance.setInstance(this);
      startCleanupThread();
    }
  }

  @Override
  public void stop() {
    if (isStarted.compareAndSet(true, false)) {
      stopCleanupThread();
      super.stop();
      MemoryAppenderInstance.setInstance(null);
      clearEvents();
    }
  }

  private void startCleanupThread() {
    cleanupThread =
        new Thread(
            () -> {
              while (isStarted.get()) {
                try {
                  TimeUnit.MINUTES.sleep(5); // Check every 5 minutes
                  cleanupOldEvents();
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                  break;
                }
              }
            });
    cleanupThread.setDaemon(true);
    cleanupThread.start();
  }

  private void stopCleanupThread() {
    if (cleanupThread != null) {
      cleanupThread.interrupt();
      cleanupThread = null;
    }
  }

  private void cleanupOldEvents() {
    long now = System.currentTimeMillis();
    List<LogEvent> toRemove = new ArrayList<>();

    events.forEach(
        event -> {
          if (now - event.timestamp > MAX_EVENT_AGE_MS) {
            toRemove.add(event);
          }
        });

    events.removeAll(toRemove);
  }

  @Override
  protected void append(ILoggingEvent event) {
    if (!isStarted.get()) {
      return;
    }
    subAppend(event);

    String stackTrace = null;
    if (event.getThrowableProxy() != null) {
      stackTrace = ThrowableProxyUtil.asString(event.getThrowableProxy());
    }
    Map<String, String> mdc =
        event.getMDCPropertyMap() != null ? new HashMap<>(event.getMDCPropertyMap()) : null;

    LogEvent logEvent =
        new LogEvent(
            event.getFormattedMessage(),
            new String(this.encoder.encode(event)),
            System.currentTimeMillis(),
            event.getLevel() != null ? event.getLevel().toString() : null,
            event.getLoggerName(),
            event.getThreadName(),
            mdc,
            stackTrace);

    if (!events.offer(logEvent)) {
      // If queue is full, remove oldest and try again
      events.poll();
      events.offer(logEvent);
    }
  }

  public List<String> getEventsAsList() {
    List<String> result = new ArrayList<>();

    // Create a snapshot of the queue to avoid concurrent modification issues
    List<LogEvent> snapshot = new ArrayList<>(events);

    for (LogEvent event : snapshot) {
      result.add(event.formattedMessage != null ? event.formattedMessage : event.message);
    }

    return result;
  }

  /**
   * Get events within a specific time range
   *
   * @param startTimeMs Start time in milliseconds since epoch
   * @param endTimeMs End time in milliseconds since epoch
   * @return List of log messages within the time range
   */
  public List<String> getEventsInTimeRange(long startTimeMs, long endTimeMs) {
    List<String> result = new ArrayList<>();

    // Create a snapshot of the queue to avoid concurrent modification issues
    List<LogEvent> snapshot = new ArrayList<>(events);

    for (LogEvent event : snapshot) {
      if (event.timestamp >= startTimeMs && event.timestamp <= endTimeMs) {
        result.add(event.formattedMessage != null ? event.formattedMessage : event.message);
      }
    }

    return result;
  }

  /**
   * Get all events with their timestamps
   *
   * @return List of LogEvent objects containing both message and timestamp
   */
  public List<LogEvent> getEventsWithTimestamps() {
    List<LogEvent> result = new ArrayList<>();

    // Create a snapshot of the queue to avoid concurrent modification issues
    List<LogEvent> snapshot = new ArrayList<>(events);

    for (LogEvent event : snapshot) {
      result.add(
          new LogEvent(
              event.message, event.formattedMessage, event.timestamp, event.level,
              event.loggerName, event.threadName, event.mdc, event.stackTrace));
    }

    return result;
  }

  public void enableLogShipper(LogShipper shipper) {
    addInfo("Log shipper enabled");
    if (this.shipper == null) {
      this.shipper = shipper;
      startShipperThread();
    }
  }

  public void disableLogShipper() {
    addInfo("Log shipper disabled");
    stopShipperThread();
    if (this.shipper != null) {
      this.shipper.stop();
      this.shipper = null;
    }
  }

  private void startShipperThread() {
    shipperThread =
        new Thread(
            () -> {
              while (isStarted.get() && shipper != null) {
                try {
                  TimeUnit.SECONDS.sleep(10);
                  drainToShipper();
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                  break;
                }
              }
            });
    shipperThread.setDaemon(true);
    shipperThread.setName("exile-log-shipper");
    shipperThread.start();
  }

  private void stopShipperThread() {
    if (shipperThread != null) {
      shipperThread.interrupt();
      shipperThread = null;
    }
  }

  private void drainToShipper() {
    if (shipper == null || events.isEmpty()) return;
    List<LogEvent> batch = new ArrayList<>();
    events.drainTo(batch);
    if (!batch.isEmpty()) {
      shipper.shipStructuredLogs(batch);
    }
  }

  public void clearEvents() {
    events.clear();
  }

  public static class LogEvent {
    /** Raw log message (no pattern formatting). */
    public final String message;
    /** Formatted log line from the encoder pattern (for display/legacy). */
    public final String formattedMessage;
    public final long timestamp;
    public final String level;
    public final String loggerName;
    public final String threadName;
    public final Map<String, String> mdc;
    public final String stackTrace;

    public LogEvent(String message, long timestamp) {
      this(message, null, timestamp, null, null, null, null, null);
    }

    public LogEvent(
        String message,
        String formattedMessage,
        long timestamp,
        String level,
        String loggerName,
        String threadName,
        Map<String, String> mdc,
        String stackTrace) {
      this.message = message;
      this.formattedMessage = formattedMessage;
      this.timestamp = timestamp;
      this.level = level;
      this.loggerName = loggerName;
      this.threadName = threadName;
      this.mdc = mdc;
      this.stackTrace = stackTrace;
    }
  }
}
