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
package com.tcn.exile.handler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.tcn.exile.memlogger.MemoryAppender;
import com.tcn.exile.service.ConfigService;
import org.junit.jupiter.api.Test;

public class PluginBaseTest {

  private static final class TestPlugin extends PluginBase {
    @Override
    public boolean onConfig(ConfigService.ClientConfiguration config) {
      return true;
    }
  }

  private static ch.qos.logback.classic.Logger logger(String name) {
    var ctx = (ch.qos.logback.classic.LoggerContext) org.slf4j.LoggerFactory.getILoggerFactory();
    return ctx.getLogger(name);
  }

  private static MemoryAppender startedAppender() {
    var ctx = (ch.qos.logback.classic.LoggerContext) org.slf4j.LoggerFactory.getILoggerFactory();

    var encoder = new ch.qos.logback.classic.encoder.PatternLayoutEncoder();
    encoder.setContext(ctx);
    encoder.setPattern("%msg%n");
    encoder.start();

    var appender = new MemoryAppender();
    appender.setContext(ctx);
    appender.setEncoder(encoder);
    appender.start();
    return appender;
  }

  @Test
  public void listTenantLogsReturnsEveryEntryWhenNoPageSizeIsRequested() throws Exception {
    var appender = startedAppender();
    var chatty = logger("test.tenantlogs.pagesize");
    chatty.setLevel(ch.qos.logback.classic.Level.INFO);
    chatty.setAdditive(false);
    chatty.addAppender(appender);

    try {
      for (int i = 0; i < 150; i++) {
        chatty.info("line {}", i);
      }
      var plugin = new TestPlugin();

      assertEquals(
          150,
          plugin.listTenantLogs(null, null, "", 0).items().size(),
          "the gate sends no page size, so a limit here silently truncates the admin log view");
      assertEquals(5, plugin.listTenantLogs(null, null, "", 5).items().size());
    } finally {
      chatty.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  public void setsKnownLevel() throws Exception {
    var plugin = new TestPlugin();
    plugin.setLogLevel("test.setlevel.known", "WARN");

    assertEquals(ch.qos.logback.classic.Level.WARN, logger("test.setlevel.known").getLevel());
  }

  @Test
  public void acceptsLowercaseLevel() throws Exception {
    var plugin = new TestPlugin();
    plugin.setLogLevel("test.setlevel.lower", "error");

    assertEquals(ch.qos.logback.classic.Level.ERROR, logger("test.setlevel.lower").getLevel());
  }

  @Test
  public void rejectsLevelNamesLogbackDoesNotKnow() {
    var plugin = new TestPlugin();

    for (var bogus : new String[] {"WARNING", "FATAL", "", "nonsense"}) {
      assertThrows(
          IllegalArgumentException.class,
          () -> plugin.setLogLevel("test.setlevel.bogus", bogus),
          "expected '" + bogus + "' to be rejected");
    }
    assertEquals(
        null, logger("test.setlevel.bogus").getLevel(), "level left untouched after rejection");
  }

  @Test
  public void reportsEffectiveLevelsNotJustExplicitOnes() throws Exception {
    var plugin = new TestPlugin();
    logger("test.levels.explicit").setLevel(ch.qos.logback.classic.Level.WARN);
    var inheriting = logger("test.levels.inheriting");
    inheriting.setLevel(null);

    var levels = plugin.loggerLevels();

    assertEquals("WARN", levels.get("test.levels.explicit"));
    assertEquals(
        inheriting.getEffectiveLevel().toString(),
        levels.get("test.levels.inheriting"),
        "a logger inheriting its level is still adjustable and must be reported");
  }

  @Test
  public void loggerLevelsRoundTripsThroughSetLogLevel() throws Exception {
    var plugin = new TestPlugin();
    plugin.setLogLevel("test.levels.roundtrip", "ERROR");

    assertEquals("ERROR", plugin.loggerLevels().get("test.levels.roundtrip"));
  }

  @Test
  public void rejectsNullLevel() {
    var plugin = new TestPlugin();
    assertThrows(
        IllegalArgumentException.class, () -> plugin.setLogLevel("test.setlevel.null", null));
  }
}
