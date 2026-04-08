package com.tcn.exile;

import com.tcn.exile.handler.EventHandler;
import com.tcn.exile.handler.JobHandler;
import com.tcn.exile.internal.ChannelFactory;
import com.tcn.exile.internal.WorkStreamClient;
import com.tcn.exile.service.*;
import io.grpc.ManagedChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tcnapi.exile.worker.v3.WorkType;

/**
 * Main entry point for the Exile client library.
 *
 * <p>Connects to the gate server, opens a work stream, and exposes domain service clients for
 * making unary RPCs.
 *
 * <p>Usage:
 *
 * <pre>{@code
 * var client = ExileClient.builder()
 *     .config(exileConfig)
 *     .clientName("sati-finvi-prod-1")
 *     .clientVersion("3.0.0")
 *     .maxConcurrency(5)
 *     .jobHandler(myJobHandler)
 *     .eventHandler(myEventHandler)
 *     .build();
 *
 * client.start();
 *
 * // Use domain services for unary RPCs.
 * var agents = client.agents().listAgents(ListAgentsRequest.getDefaultInstance());
 * var status = client.calls().getRecordingStatus(req);
 *
 * // When done:
 * client.close();
 * }</pre>
 */
public final class ExileClient implements AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(ExileClient.class);

  private final ExileConfig config;
  private final WorkStreamClient workStream;

  // Shared channel for unary RPCs (separate from the stream channel).
  private final ManagedChannel serviceChannel;

  // Domain service clients.
  private final AgentService agentService;
  private final CallService callService;
  private final RecordingService recordingService;
  private final ScrubListService scrubListService;
  private final ConfigService configService;

  private ExileClient(Builder builder) {
    this.config = builder.config;

    this.workStream =
        new WorkStreamClient(
            config,
            builder.jobHandler,
            builder.eventHandler,
            builder.clientName,
            builder.clientVersion,
            builder.maxConcurrency,
            builder.capabilities);

    // Create a shared channel for unary RPCs.
    this.serviceChannel = ChannelFactory.create(config);
    var services = ServiceFactory.create(serviceChannel);
    this.agentService = services.agent();
    this.callService = services.call();
    this.recordingService = services.recording();
    this.scrubListService = services.scrubList();
    this.configService = services.config();
  }

  /** Start the work stream. Call this after building the client. */
  public void start() {
    log.info("Starting ExileClient for org={}", config.org());
    workStream.start();
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

  @Override
  public void close() {
    log.info("Shutting down ExileClient");
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
    private List<WorkType> capabilities = new ArrayList<>();

    private Builder() {}

    /** Required. Connection configuration (certs, endpoint). */
    public Builder config(ExileConfig config) {
      this.config = Objects.requireNonNull(config);
      return this;
    }

    /**
     * Job handler implementation. Defaults to a no-op handler that rejects all jobs with
     * UnsupportedOperationException.
     */
    public Builder jobHandler(JobHandler jobHandler) {
      this.jobHandler = Objects.requireNonNull(jobHandler);
      return this;
    }

    /**
     * Event handler implementation. Defaults to a no-op handler that acknowledges all events
     * without processing.
     */
    public Builder eventHandler(EventHandler eventHandler) {
      this.eventHandler = Objects.requireNonNull(eventHandler);
      return this;
    }

    /** Human-readable client name for diagnostics. */
    public Builder clientName(String clientName) {
      this.clientName = Objects.requireNonNull(clientName);
      return this;
    }

    /** Client software version for diagnostics. */
    public Builder clientVersion(String clientVersion) {
      this.clientVersion = Objects.requireNonNull(clientVersion);
      return this;
    }

    /**
     * Maximum number of work items to process concurrently. Controls the Pull(max_items) sent to
     * the server. Default: 5.
     */
    public Builder maxConcurrency(int maxConcurrency) {
      if (maxConcurrency < 1) throw new IllegalArgumentException("maxConcurrency must be >= 1");
      this.maxConcurrency = maxConcurrency;
      return this;
    }

    /**
     * Work types this client can handle. Empty (default) means all types. Use this to limit what
     * the server dispatches to this client.
     */
    public Builder capabilities(List<WorkType> capabilities) {
      this.capabilities = new ArrayList<>(Objects.requireNonNull(capabilities));
      return this;
    }

    public ExileClient build() {
      Objects.requireNonNull(config, "config is required");
      return new ExileClient(this);
    }
  }
}
