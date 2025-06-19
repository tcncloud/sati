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
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class MemoryAppender extends OutputStreamAppender<ILoggingEvent> {
  private static final int MAX_SIZE = 100;
  private static final long MAX_EVENT_AGE_MS = 3600000; // 1 hour
  private final BlockingQueue<LogEvent> events;
  private LogShipper shipper = null;
  private final AtomicBoolean isStarted = new AtomicBoolean(false);
  private Thread cleanupThread;

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

    LogEvent logEvent =
        new LogEvent(new String(this.encoder.encode(event)), System.currentTimeMillis());

    if (!events.offer(logEvent)) {
      // If queue is full, remove oldest and try again
      events.poll();
      events.offer(logEvent);
    }

    if (shipper != null) {
      List<String> eventsToShip = getEventsAsList();
      if (!eventsToShip.isEmpty()) {
        shipper.shipLogs(eventsToShip);
        events.clear();
      }
    }
  }

  public List<String> getEventsAsList() {
    List<String> result = new ArrayList<>();
    events.forEach(event -> result.add(event.message));
    return result;
  }

  public void enableLogShipper(LogShipper shipper) {
    addInfo("Log shipper enabled");
    if (this.shipper == null) {
      this.shipper = shipper;
      List<String> eventsToShip = getEventsAsList();
      if (!eventsToShip.isEmpty()) {
        shipper.shipLogs(eventsToShip);
        events.clear();
      }
    }
  }

  public void disableLogShipper() {
    addInfo("Log shipper disabled");
    if (this.shipper != null) {
      this.shipper.stop();
      this.shipper = null;
    }
  }

  public void clearEvents() {
    events.clear();
  }

  private static class LogEvent {
    final String message;
    final long timestamp;

    LogEvent(String message, long timestamp) {
      this.message = message;
      this.timestamp = timestamp;
    }
  }
}
