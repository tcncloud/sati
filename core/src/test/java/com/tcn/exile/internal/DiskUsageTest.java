package com.tcn.exile.internal;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DiskUsageTest {

  @Test
  void reportsThePercentUsedOfTheWorkingDirectoryDisk() throws Exception {
    var pct = MetricsManager.diskUsedPercent(Path.of(System.getProperty("user.dir")));
    assertTrue(pct >= 0 && pct <= 100, "got " + pct);
  }

  @Test
  void failsForAPathThatDoesNotExist(@TempDir Path dir) {
    assertThrows(IOException.class, () -> MetricsManager.diskUsedPercent(dir.resolve("missing")));
  }
}
