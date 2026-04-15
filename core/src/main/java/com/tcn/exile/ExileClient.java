package com.tcn.exile;

import com.tcn.exile.handler.Plugin;
import com.tcn.exile.internal.AdaptiveCapacity;
import com.tcn.exile.internal.ChannelFactory;
import com.tcn.exile.internal.GrpcLogShipper;
import com.tcn.exile.internal.MetricsManager;
import com.tcn.exile.internal.WorkStreamClient;
import com.tcn.exile.memlogger.MemoryAppender;
import com.tcn.exile.memlogger.MemoryAppenderInstance;
import com.tcn.exile.service.*;
import io.grpc.ManagedChannel;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.trace.Span;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntSupplier;
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
  private final TelemetryService telemetryService;
  private final String telemetryClientId;
  private final AdaptiveCapacity adaptive; // null when caller overrode or disabled
  private volatile MetricsManager metricsManager;

  private volatile ConfigService.ClientConfiguration lastConfig;
  private final AtomicBoolean pluginReady = new AtomicBoolean(false);
  private final AtomicBoolean workStreamStarted = new AtomicBoolean(false);

  private ExileClient(Builder builder) {
    this.config = builder.config;
    this.plugin = builder.plugin;
    this.configPollInterval = builder.configPollInterval;

    var choice = chooseCapacityProvider(builder, plugin);
    IntSupplier capacity = choice.provider();
    this.adaptive = choice.adaptive();

    this.workStream =
        new WorkStreamClient(
            config,
            plugin,
            plugin,
            capacity,
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
    this.telemetryService = services.telemetry();

    // Telemetry client ID (stable across reconnects).
    this.telemetryClientId =
        builder.clientName + "-" + java.util.UUID.randomUUID().toString().substring(0, 8);

    // Wire OTel trace context into log events so each LogRecord carries trace_id/span_id.
    MemoryAppender.setTraceContextExtractor(
        new MemoryAppender.TraceContextExtractor() {
          @Override
          public String traceId() {
            var ctx = Span.current().getSpanContext();
            return ctx.isValid() ? ctx.getTraceId() : null;
          }

          @Override
          public String spanId() {
            var ctx = Span.current().getSpanContext();
            return ctx.isValid() ? ctx.getSpanId() : null;
          }
        });

    // Structured log shipping starts immediately (doesn't need org_id).
    var appender = MemoryAppenderInstance.getInstance();
    if (appender != null) {
      appender.enableLogShipper(new GrpcLogShipper(telemetryService, telemetryClientId));
    }

    // MetricsManager is created after first config poll (needs org_id + configName).

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

      // Only start WorkStream + metrics once plugin has explicitly accepted a config.
      if (pluginReady.get() && workStreamStarted.compareAndSet(false, true)) {
        // Initialize MetricsManager now that we have org_id and certificate_name.
        this.metricsManager =
            new MetricsManager(
                telemetryService,
                telemetryClientId,
                newConfig.orgId(),
                config.certificateName(),
                workStream::status);
        workStream.setDurationRecorder(metricsManager::recordWorkDuration);
        workStream.setMethodRecorder(metricsManager::recordMethodCall);
        workStream.setReconnectRecorder(metricsManager::recordReconnectDuration);

        // Feed job completions into the adaptive controller (if active) and
        // register the corresponding OTel gauges so dashboards can see the
        // controller's state. Events are deliberately a no-op — they don't
        // drive the job-SLO signal.
        if (adaptive != null) {
          workStream.setJobCompletionRecorder(adaptive::recordJobCompletion);
          workStream.setEventCompletionRecorder(adaptive::recordEventCompletion);
          metricsManager.registerAdaptiveGauges(adaptive);
        }

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

  /**
   * Snapshot of the adaptive concurrency controller's current state, for plugin diagnostics,
   * dashboards, and custom metric export. Returns empty when adaptive is disabled — either the
   * caller passed an explicit {@link Builder#capacityProvider} or called {@code adaptive(false)}.
   *
   * <p>Polling this is cheap: all reads are from {@code volatile} fields or {@code AtomicLong}s.
   * Safe to call from any thread, including plugin handler virtual threads.
   */
  public Optional<AdaptiveSnapshot> adaptiveSnapshot() {
    var a = adaptive;
    if (a == null) {
      return Optional.empty();
    }
    return Optional.of(
        new AdaptiveSnapshot(
            a.limit(),
            a.minLimit(),
            a.maxLimit(),
            a.effectiveCeiling(),
            a.jobP95Nanos(),
            a.jobEmaNanos(),
            a.decayingMinNanos(),
            a.lastSloGradient(),
            a.lastMinGradient(),
            a.lastResourceGradient(),
            a.errorCount(),
            a.sampleCount()));
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
   * are exported alongside sati's built-in metrics to the gate TelemetryService. Returns null if
   * the first config poll has not completed yet.
   */
  public Meter meter() {
    var mm = metricsManager;
    return mm != null ? mm.meter() : null;
  }

  @Override
  public void close() {
    log.info("Shutting down ExileClient");
    configPoller.shutdownNow();
    var appender = MemoryAppenderInstance.getInstance();
    if (appender != null) {
      appender.disableLogShipper();
    }
    var mm = metricsManager;
    if (mm != null) mm.close();
    workStream.close();
    ChannelFactory.shutdown(serviceChannel);
  }

  public static Builder builder() {
    return new Builder();
  }

  /**
   * Picks the capacity provider based on builder configuration. Precedence (highest first):
   *
   * <ol>
   *   <li>Explicit {@link Builder#capacityProvider} — caller knows what they want.
   *   <li>{@link Builder#adaptive} enabled (default) → build an {@link AdaptiveCapacity} bound to
   *       {@code [minConcurrency, initialConcurrency, maxConcurrency]} and the plugin's declared
   *       resource limits.
   *   <li>{@code adaptive(false)} opt-out → fall back to {@link Plugin#availableCapacity()}.
   * </ol>
   *
   * <p>Package-private so {@link com.tcn.exile} tests can exercise the selection logic without
   * going through a live {@link ChannelFactory}.
   */
  static CapacityChoice chooseCapacityProvider(Builder builder, Plugin plugin) {
    if (builder.capacityProvider != null) {
      return new CapacityChoice(builder.capacityProvider, null);
    }
    if (builder.adaptiveEnabled) {
      // Clamp the bounds so a caller setting only maxConcurrency (or only
      // minConcurrency) doesn't collide with the other defaults.
      //   min        = clamp(min,     1,      max)
      //   initial    = clamp(initial, min,    max)
      int max = builder.maxConcurrency;
      int min = Math.max(1, Math.min(builder.minConcurrency, max));
      int initial = Math.max(min, Math.min(builder.initialConcurrency, max));
      var adaptive = new AdaptiveCapacity(min, initial, max, plugin::resourceLimits);
      return new CapacityChoice(adaptive, adaptive);
    }
    return new CapacityChoice(plugin::availableCapacity, null);
  }

  /** Result of {@link #chooseCapacityProvider}: the provider and (when adaptive) the controller. */
  record CapacityChoice(IntSupplier provider, AdaptiveCapacity adaptive) {}

  public static final class Builder {
    private ExileConfig config;
    private Plugin plugin;
    private String clientName = "sati";
    private String clientVersion = "unknown";
    // Concurrency bounds. maxConcurrency was 5 pre-C4; raised to 100 to match the
    // server-advertised Registered.max_inflight. With AdaptiveCapacity on by
    // default, this is now a safety ceiling, not the operating point.
    private int minConcurrency = 1;
    private int initialConcurrency = 10;
    private int maxConcurrency = 100;
    private boolean adaptiveEnabled = true;
    private IntSupplier capacityProvider;
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

    /** Lower bound of the adaptive controller's limit. Default: 1. */
    public Builder minConcurrency(int n) {
      if (n < 1) throw new IllegalArgumentException("minConcurrency must be >= 1");
      this.minConcurrency = n;
      return this;
    }

    /**
     * Starting value of the adaptive controller's limit before enough samples accumulate. Default:
     * 10. Must satisfy {@code minConcurrency <= initialConcurrency <= maxConcurrency} — validated
     * lazily when the controller is constructed.
     */
    public Builder initialConcurrency(int n) {
      if (n < 1) throw new IllegalArgumentException("initialConcurrency must be >= 1");
      this.initialConcurrency = n;
      return this;
    }

    /**
     * Enable or disable the adaptive concurrency controller. When {@code true} (default) an {@link
     * AdaptiveCapacity} is used as the capacity provider unless the caller provided one explicitly
     * via {@link #capacityProvider}. When {@code false} the fallback is {@link
     * Plugin#availableCapacity()}, which preserves pre-C4 behaviour. Useful for deterministic
     * testing or when the plugin already has its own controller.
     */
    public Builder adaptive(boolean enabled) {
      this.adaptiveEnabled = enabled;
      return this;
    }

    /**
     * Custom capacity provider that controls how many work items sati requests from the gate. The
     * supplier should return the number of additional items the plugin can accept right now. If not
     * set, falls back to {@link Plugin#availableCapacity()}. The value is capped by {@link
     * #maxConcurrency}.
     */
    public Builder capacityProvider(IntSupplier capacityProvider) {
      this.capacityProvider = capacityProvider;
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
