package com.tcn.exile.config;

import com.tcn.exile.ExileClient;
import com.tcn.exile.ExileConfig;
import com.tcn.exile.StreamStatus;
import com.tcn.exile.handler.Plugin;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages the lifecycle of a single-tenant {@link ExileClient} driven by a config file.
 *
 * <p>Usage:
 *
 * <pre>{@code
 * var manager = ExileClientManager.builder()
 *     .clientName("sati-finvi")
 *     .clientVersion("3.0.0")
 *     .maxConcurrency(5)
 *     .plugin(new FinviPlugin(dataSource))
 *     .build();
 *
 * manager.start();
 * }</pre>
 */
public final class ExileClientManager implements AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(ExileClientManager.class);

  private final String clientName;
  private final String clientVersion;
  private final int maxConcurrency;
  private final Plugin plugin;
  private final List<Path> watchDirs;
  private final int certRotationHours;

  private volatile ExileClient activeClient;
  private volatile ExileConfig activeConfig;
  private volatile String activeOrg;
  private ConfigFileWatcher watcher;
  private ScheduledExecutorService scheduler;

  private ExileClientManager(Builder builder) {
    this.clientName = builder.clientName;
    this.clientVersion = builder.clientVersion;
    this.maxConcurrency = builder.maxConcurrency;
    this.plugin = builder.plugin;
    this.watchDirs = builder.watchDirs;
    this.certRotationHours = builder.certRotationHours;
  }

  /** Start watching the config file and managing the client lifecycle. */
  public void start() throws IOException {
    watcher =
        watchDirs != null
            ? ConfigFileWatcher.create(watchDirs, new WatcherListener())
            : ConfigFileWatcher.create(new WatcherListener());
    watcher.start();

    scheduler =
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              var t = new Thread(r, "exile-cert-rotator");
              t.setDaemon(true);
              return t;
            });
    var rotator = new CertificateRotator(this);
    scheduler.scheduleAtFixedRate(
        () -> {
          try {
            rotator.checkAndRotate();
          } catch (Exception e) {
            log.warn("Certificate rotation check failed: {}", e.getMessage());
          }
        },
        certRotationHours,
        certRotationHours,
        TimeUnit.HOURS);

    log.info(
        "ExileClientManager started (clientName={}, plugin={})", clientName, plugin.pluginName());
  }

  /** Returns the currently active client, or null if no config is loaded. */
  public ExileClient client() {
    return activeClient;
  }

  /** Returns a snapshot of the work stream status, or null if no client is active. */
  public StreamStatus streamStatus() {
    var c = activeClient;
    return c != null ? c.streamStatus() : null;
  }

  ConfigFileWatcher configWatcher() {
    return watcher;
  }

  public void stop() {
    log.info("Stopping ExileClientManager");
    destroyClient();
    if (watcher != null) watcher.close();
    if (scheduler != null) scheduler.shutdownNow();
  }

  @Override
  public void close() {
    stop();
  }

  private void createClient(ExileConfig config) {
    log.info(
        "Creating ExileClient (endpoint={}:{}, org={}, plugin={})",
        config.apiHostname(),
        config.apiPort(),
        config.org(),
        plugin.pluginName());
    var newOrg = config.org();
    if (activeOrg != null && !activeOrg.equals(newOrg)) {
      log.info("Org changed from {} to {}, destroying old client", activeOrg, newOrg);
      destroyClient();
    }

    destroyClient();

    try {
      activeClient =
          ExileClient.builder()
              .config(config)
              .clientName(clientName)
              .clientVersion(clientVersion)
              .maxConcurrency(maxConcurrency)
              .plugin(plugin)
              .build();
      activeClient.start();
      activeConfig = config;
      activeOrg = newOrg;
      log.info("ExileClient started for org={}", newOrg);
    } catch (Exception e) {
      log.error("Failed to create ExileClient for org={}: {}", newOrg, e.getMessage(), e);
      destroyClient();
    }
  }

  private void destroyClient() {
    var c = activeClient;
    if (c != null) {
      try {
        c.close();
        log.info("ExileClient closed for org={}", activeOrg);
      } catch (Exception e) {
        log.warn("Error closing ExileClient: {}", e.getMessage());
      }
      activeClient = null;
      activeConfig = null;
    }
  }

  private class WatcherListener implements ConfigFileWatcher.Listener {
    @Override
    public void onConfigChanged(ExileConfig config) {
      createClient(config);
    }

    @Override
    public void onConfigRemoved() {
      log.info("Config removed, destroying client");
      destroyClient();
      activeOrg = null;
    }
  }

  public static Builder builder() {
    return new Builder();
  }

  public static final class Builder {
    private String clientName = "sati";
    private String clientVersion = "unknown";
    private int maxConcurrency = 100;
    private Plugin plugin;
    private List<Path> watchDirs;
    private int certRotationHours = 1;

    private Builder() {}

    public Builder clientName(String clientName) {
      this.clientName = Objects.requireNonNull(clientName);
      return this;
    }

    public Builder clientVersion(String clientVersion) {
      this.clientVersion = Objects.requireNonNull(clientVersion);
      return this;
    }

    public Builder maxConcurrency(int maxConcurrency) {
      this.maxConcurrency = maxConcurrency;
      return this;
    }

    /** The plugin that handles jobs, events, and config validation. */
    public Builder plugin(Plugin plugin) {
      this.plugin = Objects.requireNonNull(plugin);
      return this;
    }

    public Builder watchDirs(List<Path> watchDirs) {
      this.watchDirs = watchDirs;
      return this;
    }

    public Builder certRotationHours(int hours) {
      this.certRotationHours = hours;
      return this;
    }

    public ExileClientManager build() {
      Objects.requireNonNull(plugin, "plugin is required");
      return new ExileClientManager(this);
    }
  }
}
