package com.tcn.exile;

import com.tcn.exile.handler.Plugin;
import com.tcn.exile.internal.ChannelFactory;
import com.tcn.exile.internal.GrpcLogShipper;
import com.tcn.exile.internal.MetricsManager;
import com.tcn.exile.internal.WorkStreamClient;
import com.tcn.exile.memlogger.MemoryAppenderInstance;
import com.tcn.exile.service.*;
import io.opentelemetry.api.metrics.Meter;
import io.grpc.ManagedChannel;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Main entry point for the Exile client library.
 *
 * <p>On {@link #start()}, only the config poller begins. The WorkStream does not open until:
 *
 * <ol>
 *   <li>The first successful config poll from the gate
 *   <li>The {@link Plugin#onConfig} returns {@code true}
 * </ol>
 */
public final class ExileClient implements AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(ExileClient.class);

  private final ExileConfig config;
  private final Plugin plugin;
  private final WorkStreamClient workStream;
  private final ManagedChannel serviceChannel;
  private final ScheduledExecutorService configPoller;
  private final Duration configPollInterval;

  private final AgentService agentService;
  private final CallService callService;
  private final RecordingService recordingService;
  private final ScrubListService scrubListService;
  private final ConfigService configService;
  private final JourneyService journeyService;
  private final MetricsManager metricsManager;

  private volatile ConfigService.ClientConfiguration lastConfig;
  private final AtomicBoolean pluginReady = new AtomicBoolean(false);
  private final AtomicBoolean workStreamStarted = new AtomicBoolean(false);

  private ExileClient(Builder builder) {
    this.config = builder.config;
    this.plugin = builder.plugin;
    this.configPollInterval = builder.configPollInterval;

    this.workStream =
        new WorkStreamClient(
            config,
            plugin,
            plugin,
            builder.clientName,
            builder.clientVersion,
            builder.maxConcurrency,
            builder.capabilities);

    this.serviceChannel = ChannelFactory.create(config);
    var services = ServiceFactory.create(serviceChannel);
    this.agentService = services.agent();
    this.callService = services.call();
    this.recordingService = services.recording();
    this.scrubListService = services.scrubList();
    this.configService = services.config();
    this.journeyService = services.journey();

    // Telemetry: OTel metrics exported via gRPC to the gate.
    var telemetryClientId = builder.clientName + "-" + java.util.UUID.randomUUID().toString().substring(0, 8);
    this.metricsManager = new MetricsManager(services.telemetry(), telemetryClientId, workStream::status);
    workStream.setDurationRecorder(metricsManager::recordWorkDuration);

    // Telemetry: structured log shipping via gRPC to the gate.
    var appender = MemoryAppenderInstance.getInstance();
    if (appender != null) {
      appender.enableLogShipper(new GrpcLogShipper(services.telemetry(), telemetryClientId));
    }

    this.configPoller =
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              var t = new Thread(r, "exile-config-poller");
              t.setDaemon(true);
              return t;
            });
  }

  /**
   * Start the client. Only the config poller begins immediately. The WorkStream opens after the
   * plugin accepts the first config via {@link Plugin#onConfig}.
   */
  public void start() {
    log.info(
        "Starting ExileClient for org={} (plugin={}, waiting for config)",
        config.org(),
        plugin.pluginName());

    configPoller.scheduleAtFixedRate(
        this::pollConfig, 0, configPollInterval.toSeconds(), TimeUnit.SECONDS);
  }

  private void pollConfig() {
    try {
      var newConfig = configService.getClientConfiguration();
      if (newConfig == null) return;

      boolean changed = lastConfig == null || !newConfig.equals(lastConfig);
      lastConfig = newConfig;

      if (changed) {
        log.info(
            "Config received from gate (org={}, configName={})",
            newConfig.orgId(),
            newConfig.configName());

        boolean ready;
        try {
          ready = plugin.onConfig(newConfig);
        } catch (Exception e) {
          log.warn("Plugin {} rejected config: {}", plugin.pluginName(), e.getMessage());
          pluginReady.set(false);
          return;
        }

        if (!ready) {
          log.warn("Plugin {} not ready — WorkStream will not start yet", plugin.pluginName());
          pluginReady.set(false);
          return;
        }

        pluginReady.set(true);
      }

      // Only start WorkStream if plugin has explicitly accepted a config.
      if (pluginReady.get() && workStreamStarted.compareAndSet(false, true)) {
        log.info("Plugin {} ready, starting WorkStream", plugin.pluginName());
        workStream.start();
      }
    } catch (Exception e) {
      log.debug("Config poll failed ({}): {}", e.getClass().getSimpleName(), e.getMessage());
    }
  }

  /** Returns the last polled config from the gate, or null if never polled. */
  public ConfigService.ClientConfiguration lastPolledConfig() {
    return lastConfig;
  }

  public StreamStatus streamStatus() {
    return workStream.status();
  }

  public ExileConfig config() {
    return config;
  }

  public Plugin plugin() {
    return plugin;
  }

  public AgentService agents() {
    return agentService;
  }

  public CallService calls() {
    return callService;
  }

  public RecordingService recordings() {
    return recordingService;
  }

  public ScrubListService scrubLists() {
    return scrubListService;
  }

  public ConfigService config_() {
    return configService;
  }

  public JourneyService journey() {
    return journeyService;
  }

  /**
   * The OTel Meter for registering custom metrics from plugins. Instruments created on this meter
   * are exported alongside sati's built-in metrics to the gate TelemetryService.
   */
  public Meter meter() {
    return metricsManager.meter();
  }

  @Override
  public void close() {
    log.info("Shutting down ExileClient");
    configPoller.shutdownNow();
    var appender = MemoryAppenderInstance.getInstance();
    if (appender != null) {
      appender.disableLogShipper();
    }
    metricsManager.close();
    workStream.close();
    ChannelFactory.shutdown(serviceChannel);
  }

  public static Builder builder() {
    return new Builder();
  }

  public static final class Builder {
    private ExileConfig config;
    private Plugin plugin;
    private String clientName = "sati";
    private String clientVersion = "unknown";
    private int maxConcurrency = 5;
    private List<build.buf.gen.tcnapi.exile.gate.v3.WorkType> capabilities = new ArrayList<>();
    private Duration configPollInterval = Duration.ofSeconds(10);

    private Builder() {}

    public Builder config(ExileConfig config) {
      this.config = Objects.requireNonNull(config);
      return this;
    }

    /** The plugin that handles jobs, events, and config validation. */
    public Builder plugin(Plugin plugin) {
      this.plugin = Objects.requireNonNull(plugin);
      return this;
    }

    public Builder clientName(String clientName) {
      this.clientName = Objects.requireNonNull(clientName);
      return this;
    }

    public Builder clientVersion(String clientVersion) {
      this.clientVersion = Objects.requireNonNull(clientVersion);
      return this;
    }

    public Builder maxConcurrency(int maxConcurrency) {
      if (maxConcurrency < 1) throw new IllegalArgumentException("maxConcurrency must be >= 1");
      this.maxConcurrency = maxConcurrency;
      return this;
    }

    /** How often to poll the gate for config updates. Default: 10 seconds. */
    public Builder configPollInterval(Duration interval) {
      this.configPollInterval = Objects.requireNonNull(interval);
      return this;
    }

    public ExileClient build() {
      Objects.requireNonNull(config, "config is required");
      Objects.requireNonNull(plugin, "plugin is required");
      return new ExileClient(this);
    }
  }
}
