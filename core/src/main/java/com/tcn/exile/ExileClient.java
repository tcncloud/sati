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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Main entry point for the Exile client library.
 *
 * <p>On {@link #start()}, only the config poller begins. The WorkStream does not open until the
 * first successful config poll from the gate and the {@code onConfigPolled} callback completes
 * without error. This ensures the integration's resources (database, HTTP client) are initialized
 * before any work items arrive.
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
  private final AtomicBoolean workStreamStarted = new AtomicBoolean(false);

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

  /**
   * Start the client. Only the config poller begins immediately. The WorkStream opens after the
   * first successful config poll and onConfigPolled callback.
   */
  public void start() {
    log.info(
        "Starting ExileClient for org={} (waiting for gate config before opening stream)",
        config.org());

    configPoller.scheduleAtFixedRate(
        this::pollConfig,
        0, // poll immediately on start
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

      // Start WorkStream on first successful config poll.
      if (workStreamStarted.compareAndSet(false, true)) {
        log.info("Gate config received, starting WorkStream");
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
     * Callback invoked when the gate returns a new or changed client configuration. On the first
     * successful poll, this fires before the WorkStream opens — use it to initialize database
     * connections, HTTP clients, or other resources that job/event handlers depend on.
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
