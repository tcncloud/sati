package com.tcn.exile.config;

import com.tcn.exile.ExileClient;
import com.tcn.exile.ExileConfig;
import com.tcn.exile.StreamStatus;
import com.tcn.exile.handler.EventHandler;
import com.tcn.exile.handler.JobHandler;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages the lifecycle of a single-tenant {@link ExileClient} driven by a config file.
 *
 * <p>Replaces the 400-500 line ConfigChangeWatcher that was copy-pasted across finvi, capone,
 * latitude, and debtnet. Handles:
 *
 * <ul>
 *   <li>Config file watching and parsing
 *   <li>ExileClient creation and destruction on config changes
 *   <li>Org change detection (destroys old client, creates new one)
 *   <li>Periodic certificate rotation
 *   <li>Graceful shutdown
 * </ul>
 *
 * <p>Usage:
 *
 * <pre>{@code
 * var manager = ExileClientManager.builder()
 *     .clientName("sati-finvi")
 *     .clientVersion("3.0.0")
 *     .maxConcurrency(5)
 *     .jobHandler(new FinviJobHandler(dataSource))
 *     .eventHandler(new FinviEventHandler(dataSource))
 *     .onConfigChange(config -> reinitializeDataSource(config))
 *     .build();
 *
 * manager.start();
 *
 * // Access the active client.
 * var agents = manager.client().agents().listAgents(...);
 *
 * // Check health.
 * var status = manager.client().streamStatus();
 *
 * // Shut down.
 * manager.stop();
 * }</pre>
 */
public final class ExileClientManager implements AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(ExileClientManager.class);

  private final String clientName;
  private final String clientVersion;
  private final int maxConcurrency;
  private final JobHandler jobHandler;
  private final EventHandler eventHandler;
  private final Consumer<ExileConfig> onConfigChange;
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
    this.jobHandler = builder.jobHandler;
    this.eventHandler = builder.eventHandler;
    this.onConfigChange = builder.onConfigChange;
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

    // Schedule certificate rotation.
    scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
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

    log.info("ExileClientManager started (clientName={})", clientName);
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

  /** Returns the config file watcher (for writing rotated certs). */
  ConfigFileWatcher configWatcher() {
    return watcher;
  }

  /** Stop the manager, close the client, and stop watching. */
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
    // If org changed, destroy the old client first.
    var newOrg = config.org();
    if (activeOrg != null && !activeOrg.equals(newOrg)) {
      log.info("Org changed from {} to {}, destroying old client", activeOrg, newOrg);
      destroyClient();
    }

    // Notify integration of config change (e.g., reinit datasource).
    if (onConfigChange != null) {
      try {
        onConfigChange.accept(config);
      } catch (Exception e) {
        log.error("onConfigChange callback failed: {}", e.getMessage(), e);
        return;
      }
    }

    // Destroy existing client if any (handles reconnect with new certs).
    destroyClient();

    try {
      activeClient =
          ExileClient.builder()
              .config(config)
              .clientName(clientName)
              .clientVersion(clientVersion)
              .maxConcurrency(maxConcurrency)
              .jobHandler(jobHandler)
              .eventHandler(eventHandler)
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
    private int maxConcurrency = 5;
    private JobHandler jobHandler = new JobHandler() {};
    private EventHandler eventHandler = new EventHandler() {};
    private Consumer<ExileConfig> onConfigChange;
    private List<Path> watchDirs;
    private int certRotationHours = 1;

    private Builder() {}

    /** Human-readable client name for diagnostics. */
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

    public Builder jobHandler(JobHandler jobHandler) {
      this.jobHandler = Objects.requireNonNull(jobHandler);
      return this;
    }

    public Builder eventHandler(EventHandler eventHandler) {
      this.eventHandler = Objects.requireNonNull(eventHandler);
      return this;
    }

    /**
     * Callback invoked when config changes, before the ExileClient is (re)created. Use this to
     * reinitialize integration-specific resources like database connections.
     *
     * <p>The callback receives the new {@link ExileConfig}. If it throws, the client will not be
     * created.
     */
    public Builder onConfigChange(Consumer<ExileConfig> onConfigChange) {
      this.onConfigChange = onConfigChange;
      return this;
    }

    /**
     * Override the default config directory paths. Defaults to {@code /workdir/config} and
     * {@code workdir/config}.
     */
    public Builder watchDirs(List<Path> watchDirs) {
      this.watchDirs = watchDirs;
      return this;
    }

    /** How often to check certificate expiration (hours). Default: 1. */
    public Builder certRotationHours(int hours) {
      this.certRotationHours = hours;
      return this;
    }

    public ExileClientManager build() {
      return new ExileClientManager(this);
    }
  }
}
