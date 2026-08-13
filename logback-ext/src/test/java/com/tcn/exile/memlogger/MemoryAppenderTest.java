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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class MemoryAppenderTest {

  private LoggerContext context;
  private MemoryAppender appender;
  private Logger logger;
  private RecordingShipper shipper;

  private static final class RecordingShipper implements LogShipper {
    final List<List<MemoryAppender.LogEvent>> batches = new ArrayList<>();
    boolean failNext = false;

    @Override
    public void shipLogs(List<String> payload) {}

    @Override
    public boolean shipStructuredLogsChecked(List<MemoryAppender.LogEvent> events) {
      if (failNext) {
        return false;
      }
      batches.add(new ArrayList<>(events));
      return true;
    }

    @Override
    public void stop() {}
  }

  @BeforeEach
  public void setUp() {
    context = (LoggerContext) org.slf4j.LoggerFactory.getILoggerFactory();

    var encoder = new PatternLayoutEncoder();
    encoder.setContext(context);
    encoder.setPattern("%msg%n");
    encoder.start();

    appender = new MemoryAppender();
    appender.setContext(context);
    appender.setEncoder(encoder);
    appender.start();

    logger = context.getLogger("test.memappender." + System.nanoTime());
    logger.setLevel(Level.INFO);
    logger.setAdditive(false);
    logger.addAppender(appender);

    shipper = new RecordingShipper();
    appender.enableLogShipper(shipper);
  }

  @AfterEach
  public void tearDown() {
    logger.detachAppender(appender);
    appender.stop();
  }

  @Test
  public void shippingLeavesEventsInTheBuffer() {
    logger.info("first");
    logger.info("second");

    appender.shipPendingEvents();

    assertEquals(1, shipper.batches.size(), "one batch shipped");
    assertEquals(2, shipper.batches.get(0).size(), "both events shipped");
    assertEquals(
        2,
        appender.getEventsWithTimestamps().size(),
        "buffer must survive shipping so tenant-log queries can still read it");
  }

  @Test
  public void alreadyShippedEventsAreNotResent() {
    logger.info("first");
    appender.shipPendingEvents();
    appender.shipPendingEvents();

    assertEquals(1, shipper.batches.size(), "nothing new to ship on the second pass");

    logger.info("second");
    appender.shipPendingEvents();

    assertEquals(2, shipper.batches.size());
    assertEquals(1, shipper.batches.get(1).size(), "only the new event");
    assertEquals("second", shipper.batches.get(1).get(0).message);
  }

  @Test
  public void failedBatchIsRetriedNotDropped() {
    logger.info("first");

    shipper.failNext = true;
    appender.shipPendingEvents();
    assertTrue(shipper.batches.isEmpty(), "send failed, nothing recorded");

    shipper.failNext = false;
    appender.shipPendingEvents();

    assertEquals(1, shipper.batches.size(), "batch retried after the failure");
    assertEquals("first", shipper.batches.get(0).get(0).message);
  }

  @Test
  public void eventsCarryLevelAndLoggerName() {
    logger.warn("careful");

    var events = appender.getEventsWithTimestamps();
    assertEquals(1, events.size());
    assertEquals("WARN", events.get(0).level);
    assertEquals(logger.getName(), events.get(0).loggerName);
  }

  @Test
  public void stopFlushesRemainingEvents() {
    logger.info("last words");

    appender.stop();

    assertEquals(1, shipper.batches.size(), "pending events flushed on shutdown");
    assertEquals("last words", shipper.batches.get(0).get(0).message);
    assertFalse(appender.isStarted(), "appender stopped");
  }
}
