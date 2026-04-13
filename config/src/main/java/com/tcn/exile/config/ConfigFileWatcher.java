package com.tcn.exile.config;

import com.tcn.exile.ExileConfig;
import io.methvin.watcher.DirectoryWatcher;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Watches the config directory for changes to the exile config file and invokes a callback.
 *
 * <p>Replaces the 400+ line ConfigChangeWatcher that was copy-pasted across all integrations.
 *
 * <p>The watcher monitors the standard config file ({@code com.tcn.exiles.sati.config.cfg}) in the
 * standard directories ({@code /workdir/config} and {@code workdir/config}). On create/modify, it
 * parses the file and calls the configured {@link Listener}. On delete, it calls {@link
 * Listener#onConfigRemoved()}.
 */
public final class ConfigFileWatcher implements AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(ConfigFileWatcher.class);
  static final String CONFIG_FILE_NAME = "com.tcn.exiles.sati.config.cfg";
  private static final List<Path> DEFAULT_WATCH_DIRS =
      List.of(Path.of("/workdir/config"), Path.of("workdir/config"));

  private final List<Path> watchDirs;
  private final Listener listener;
  private final AtomicBoolean started = new AtomicBoolean(false);
  private volatile DirectoryWatcher watcher;
  private volatile Path configDir;

  /** Callback interface for config change events. */
  public interface Listener {
    /** Called when a new or updated config is detected. */
    void onConfigChanged(ExileConfig config);

    /** Called when the config file is deleted. */
    void onConfigRemoved();
  }

  private ConfigFileWatcher(List<Path> watchDirs, Listener listener) {
    this.watchDirs = watchDirs;
    this.listener = listener;
  }

  public static ConfigFileWatcher create(Listener listener) {
    return new ConfigFileWatcher(DEFAULT_WATCH_DIRS, listener);
  }

  public static ConfigFileWatcher create(List<Path> watchDirs, Listener listener) {
    return new ConfigFileWatcher(watchDirs, listener);
  }

  /**
   * Start watching. Reads any existing config file immediately, then watches for changes
   * asynchronously. Call this once.
   */
  public void start() throws IOException {
    if (!started.compareAndSet(false, true)) {
      throw new IllegalStateException("Already started");
    }

    // Find or create the config directory.
    configDir = watchDirs.stream().filter(p -> p.toFile().exists()).findFirst().orElse(null);
    if (configDir == null) {
      var fallback = watchDirs.get(0);
      if (fallback.toFile().mkdirs()) {
        configDir = fallback;
        log.info("Created config directory: {}", configDir);
      } else {
        log.warn("Could not find or create config directory from: {}", watchDirs);
      }
    } else {
      log.info("Using config directory: {}", configDir);
    }

    // Read existing config if present.
    loadExistingConfig();

    // Start watching.
    var existingDirs = watchDirs.stream().filter(p -> p.toFile().exists()).toList();
    if (existingDirs.isEmpty()) {
      log.warn("No config directories exist to watch");
      return;
    }

    watcher =
        DirectoryWatcher.builder()
            .paths(existingDirs)
            .fileHashing(false)
            .listener(
                event -> {
                  if (!event.path().getFileName().toString().equals(CONFIG_FILE_NAME)) return;
                  switch (event.eventType()) {
                    case CREATE, MODIFY -> handleConfigFileChange(event.path());
                    case DELETE -> {
                      log.info("Config file deleted");
                      listener.onConfigRemoved();
                    }
                    default -> log.debug("Ignoring event: {}", event.eventType());
                  }
                })
            .build();
    watcher.watchAsync();
    log.info("Config file watcher started");
  }

  /** Returns the directory where the config file was found or created. */
  public Path configDir() {
    return configDir;
  }

  /**
   * Write a config string (e.g., a rotated certificate) to the config file. This triggers the
   * watcher to reload.
   */
  public void writeConfig(String content) throws IOException {
    if (configDir == null) {
      throw new IllegalStateException("No config directory available");
    }
    var file = configDir.resolve(CONFIG_FILE_NAME);
    Files.writeString(file, content);
    log.info("Wrote config file: {}", file);
  }

  private void loadExistingConfig() {
    if (configDir == null) return;
    var file = configDir.resolve(CONFIG_FILE_NAME);
    if (file.toFile().exists()) {
      ConfigParser.parse(file)
          .ifPresent(
              config -> {
                log.info("Loaded existing config for org={}", config.org());
                listener.onConfigChanged(config);
              });
    }
  }

  private void handleConfigFileChange(Path path) {
    if (!path.toFile().canRead()) {
      log.warn("Config file not readable: {}", path);
      return;
    }
    ConfigParser.parse(path)
        .ifPresentOrElse(
            config -> {
              log.info("Config changed for org={}", config.org());
              listener.onConfigChanged(config);
            },
            () -> log.warn("Failed to parse config file: {}", path));
  }

  @Override
  public void close() {
    if (watcher != null) {
      try {
        watcher.close();
        log.info("Config file watcher closed");
      } catch (IOException e) {
        log.warn("Error closing config file watcher", e);
      }
    }
  }
}
