package com.tcn.exile.config;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import com.tcn.exile.ExileConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigFileWatcherTest {

  private ConfigFileWatcher watcher;

  @AfterEach
  void tearDown() {
    if (watcher != null) watcher.close();
  }

  private ConfigFileWatcher started(Path dir) throws IOException {
    watcher =
        ConfigFileWatcher.create(
            List.of(dir),
            new ConfigFileWatcher.Listener() {
              @Override
              public void onConfigChanged(ExileConfig config) {}

              @Override
              public void onConfigRemoved() {}
            });
    watcher.start();
    return watcher;
  }

  @Test
  void assertWritablePassesOnAWritableDirectory(@TempDir Path dir) throws IOException {
    assertDoesNotThrow(started(dir)::assertWritable);
  }

  @Test
  void assertWritableFailsOnAReadOnlyDirectory(@TempDir Path dir) throws IOException {
    // Stands in for a ConfigMap or Secret volume, which the kubelet mounts read-only.
    var w = started(dir);
    assertTrue(dir.toFile().setWritable(false));
    assumeFalse(Files.isWritable(dir), "running as root, cannot make a directory read-only");

    var e = assertThrows(IOException.class, w::assertWritable);
    assertTrue(e.getMessage().contains("not writable"), e.getMessage());

    dir.toFile().setWritable(true);
  }

  @Test
  void writeConfigReplacesTheFileAndLeavesNoTempBehind(@TempDir Path dir) throws IOException {
    var w = started(dir);
    w.writeConfig("first");
    w.writeConfig("second");

    assertEquals("second", Files.readString(w.configFile()));
    try (Stream<Path> files = Files.list(dir)) {
      assertEquals(
          List.of(ConfigFileWatcher.CONFIG_FILE_NAME),
          files.map(p -> p.getFileName().toString()).sorted().toList());
    }
  }

  @Test
  void writeConfigReplacesASymlinkInsteadOfWritingThroughIt(@TempDir Path dir) throws IOException {
    var target = Files.writeString(dir.resolve("target.cfg"), "original");
    var link = dir.resolve(ConfigFileWatcher.CONFIG_FILE_NAME);
    Files.createSymbolicLink(link, target);

    started(dir).writeConfig("rotated");

    assertFalse(Files.isSymbolicLink(link), "the symlink should be replaced, not followed");
    assertEquals("rotated", Files.readString(link));
    assertEquals("original", Files.readString(target));
  }

  @Test
  void configFileSitsInTheWatchedDirectory(@TempDir Path dir) throws IOException {
    assertEquals(dir.resolve(ConfigFileWatcher.CONFIG_FILE_NAME), started(dir).configFile());
  }
}
