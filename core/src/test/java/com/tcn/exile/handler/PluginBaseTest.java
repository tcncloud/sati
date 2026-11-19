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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tcn.exile.memlogger.MemoryAppender;
import com.tcn.exile.service.ConfigService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
  public void listTenantLogsReturnsTheEncodedLineNotTheBareMessage() throws Exception {
    var appender = startedAppender();
    var chatty = logger("test.tenantlogs.encoded");
    chatty.setLevel(ch.qos.logback.classic.Level.INFO);
    chatty.setAdditive(false);
    chatty.addAppender(appender);

    try {
      chatty.info("account {} popped", 42);

      var entries = new TestPlugin().listTenantLogs(null, null, "", 0).items();

      assertEquals(1, entries.size());
      assertEquals("account 42 popped\n", entries.get(0).message());
      assertEquals("INFO", entries.get(0).level());
      assertEquals(chatty.getName(), entries.get(0).logger());
    } finally {
      chatty.detachAppender(appender);
      appender.stop();
    }
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

  @Test
  public void diagnosticsHasTheKeysTheAdminPageShows() throws Exception {
    var d = new TestPlugin().diagnostics();

    for (var key : List.of("os.memory.total", "os.memory.free", "os.swap.total", "os.swap.free")) {
      assertTrue(d.systemInfo().containsKey(key), key);
    }
    for (var key :
        List.of(
            "java.runtime.name",
            "java.vm.vendor",
            "java.specification.name",
            "java.specification.version",
            "java.class.path",
            "start.ms",
            "non_heap.max",
            "memory_pools")) {
      assertTrue(d.runtimeInfo().containsKey(key), key);
    }
    var pools = (List<?>) d.runtimeInfo().get("memory_pools");
    assertFalse(pools.isEmpty());
    var pool = (Map<?, ?>) pools.get(0);
    assertTrue(pool.keySet().containsAll(List.of("name", "type", "used", "max", "committed")));
  }

  @Test
  public void diagnosticsSendsOnlyTheFixedEnvAndPropertyNames() throws Exception {
    var d = new TestPlugin().diagnostics();

    var env = (Map<?, ?>) d.systemInfo().get("env");
    assertTrue(PluginBase.ENV_NAMES.containsAll(env.keySet()), env.keySet().toString());
    var props = (Map<?, ?>) d.systemInfo().get("system_properties");
    assertTrue(
        PluginBase.SYSTEM_PROPERTY_NAMES.containsAll(props.keySet()), props.keySet().toString());
    assertEquals(System.getProperty("java.version"), props.get("java.version"));
    assertFalse(props.containsKey("sun.java.command"));
    assertFalse(PluginBase.SYSTEM_PROPERTY_NAMES.contains("sun.java.command"));
  }

  @Test
  public void diagnosticsNeverSendsPlaceholders() throws Exception {
    var d = new TestPlugin().diagnostics();
    for (var value : d.systemInfo().values()) {
      assertFalse("Unknown".equals(value) || "unknown".equals(value), d.systemInfo().toString());
    }
  }

  @Test
  public void containerLimitsReadCgroupV2(@TempDir Path dir) throws Exception {
    Files.writeString(dir.resolve("memory.max"), "536870912\n");
    Files.writeString(dir.resolve("cpu.max"), "150000 100000\n");

    assertEquals(
        Map.of("memory_limit", "536870912", "cpu_limit", "1.5"),
        PluginBase.containerLimits(dir.toString()));
  }

  @Test
  public void containerLimitsFallBackToCgroupV1(@TempDir Path dir) throws Exception {
    Files.createDirectories(dir.resolve("memory"));
    Files.createDirectories(dir.resolve("cpu"));
    Files.writeString(dir.resolve("memory/memory.limit_in_bytes"), "1073741824");
    Files.writeString(dir.resolve("cpu/cpu.cfs_quota_us"), "200000");
    Files.writeString(dir.resolve("cpu/cpu.cfs_period_us"), "100000");

    assertEquals(
        Map.of("memory_limit", "1073741824", "cpu_limit", "2.0"),
        PluginBase.containerLimits(dir.toString()));
  }

  @Test
  public void containerLimitsLeaveOutUnsetLimits(@TempDir Path dir) throws Exception {
    Files.writeString(dir.resolve("memory.max"), "max");
    Files.writeString(dir.resolve("cpu.max"), "max 100000");
    assertTrue(PluginBase.containerLimits(dir.toString()).isEmpty());

    Files.delete(dir.resolve("memory.max"));
    Files.delete(dir.resolve("cpu.max"));
    Files.createDirectories(dir.resolve("memory"));
    Files.createDirectories(dir.resolve("cpu"));
    Files.writeString(dir.resolve("memory/memory.limit_in_bytes"), "9223372036854771712");
    Files.writeString(dir.resolve("cpu/cpu.cfs_quota_us"), "-1");
    Files.writeString(dir.resolve("cpu/cpu.cfs_period_us"), "100000");
    assertTrue(PluginBase.containerLimits(dir.toString()).isEmpty());
  }

  @Test
  public void containerIdComesFromTheCgroupPath() {
    var id = "3f1c2b9a8d7e6f5a4b3c2d1e0f9a8b7c6d5e4f3a2b1c0d9e8f7a6b5c4d3e2f1a";
    assertEquals(
        "3f1c2b9a8d7e", PluginBase.containerId("0::/system.slice/docker-" + id + ".scope"));
    assertEquals("3f1c2b9a8d7e", PluginBase.containerId("12:memory:/docker/" + id));
    assertNull(PluginBase.containerId("0::/"));
    assertNull(PluginBase.containerId(null));
  }
}
