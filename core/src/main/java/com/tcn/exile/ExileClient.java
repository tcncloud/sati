package com.tcn.exile;

import com.tcn.exile.handler.EventHandler;
import com.tcn.exile.handler.JobHandler;
import com.tcn.exile.internal.ChannelFactory;
import com.tcn.exile.internal.WorkStreamClient;
import com.tcn.exile.service.*;
import io.grpc.ManagedChannel;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Main entry point for the Exile client library.
 *
 * <p>Connects to the gate server, opens a work stream, and exposes domain service clients for
 * making unary RPCs. Periodically polls the gate for configuration updates.
 */
public final class ExileClient implements AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(ExileClient.class);

  private final ExileConfig config;
  private final WorkStreamClient workStream;
  private final ManagedChannel serviceChannel;
  private final ScheduledExecutorService configPoller;
  private final Consumer<ConfigService.ClientConfiguration> onConfigPolled;
  private final Duration configPollInterval;

  private final AgentService agentService;
  private final CallService callService;
  private final RecordingService recordingService;
  private final ScrubListService scrubListService;
  private final ConfigService configService;
  private final JourneyService journeyService;

  private volatile ConfigService.ClientConfiguration lastConfig;

  private ExileClient(Builder builder) {
    this.config = builder.config;
    this.onConfigPolled = builder.onConfigPolled;
    this.configPollInterval = builder.configPollInterval;

    this.workStream =
        new WorkStreamClient(
            config,
            builder.jobHandler,
            builder.eventHandler,
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

    this.configPoller =
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              var t = new Thread(r, "exile-config-poller");
              t.setDaemon(true);
              return t;
            });
  }

  /** Start the work stream and config poller. */
  public void start() {
    log.info("Starting ExileClient for org={}", config.org());
    workStream.start();

    configPoller.scheduleAtFixedRate(
        this::pollConfig,
        configPollInterval.toSeconds(),
        configPollInterval.toSeconds(),
        TimeUnit.SECONDS);
  }

  private void pollConfig() {
    try {
      var newConfig = configService.getClientConfiguration();
      if (newConfig == null) return;

      boolean changed = lastConfig == null || !newConfig.equals(lastConfig);
      lastConfig = newConfig;

      if (changed && onConfigPolled != null) {
        log.info(
            "Config polled from gate (org={}, configName={}, changed=true)",
            newConfig.orgId(),
            newConfig.configName());
        onConfigPolled.accept(newConfig);
      }
    } catch (Exception e) {
      log.debug("Config poll failed (gate may not be reachable yet): {}", e.getMessage());
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

  @Override
  public void close() {
    log.info("Shutting down ExileClient");
    configPoller.shutdownNow();
    workStream.close();
    ChannelFactory.shutdown(serviceChannel);
  }

  public static Builder builder() {
    return new Builder();
  }

  public static final class Builder {
    private ExileConfig config;
    private JobHandler jobHandler = new JobHandler() {};
    private EventHandler eventHandler = new EventHandler() {};
    private String clientName = "sati";
    private String clientVersion = "unknown";
    private int maxConcurrency = 5;
    private List<build.buf.gen.tcnapi.exile.gate.v3.WorkType> capabilities = new ArrayList<>();
    private Consumer<ConfigService.ClientConfiguration> onConfigPolled;
    private Duration configPollInterval = Duration.ofSeconds(10);

    private Builder() {}

    public Builder config(ExileConfig config) {
      this.config = Objects.requireNonNull(config);
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

    /**
     * Callback invoked when the gate returns a new or changed client configuration. The config
     * payload typically contains database credentials, API keys, or plugin-specific settings. This
     * is polled from the gate every {@code configPollInterval}.
     */
    public Builder onConfigPolled(Consumer<ConfigService.ClientConfiguration> onConfigPolled) {
      this.onConfigPolled = onConfigPolled;
      return this;
    }

    /** How often to poll the gate for config updates. Default: 10 seconds. */
    public Builder configPollInterval(Duration interval) {
      this.configPollInterval = Objects.requireNonNull(interval);
      return this;
    }

    @SuppressWarnings("unchecked")
    public ExileClient build() {
      Objects.requireNonNull(config, "config is required");
      return new ExileClient(this);
    }
  }
}
