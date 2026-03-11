package com.tcn.sati.infra.gate;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tcn.sati.config.SatiConfig;
import io.grpc.Grpc;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.TlsChannelCredentials;
import java.io.ByteArrayInputStream;
import java.security.Security;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * gRPC client for connecting to Exile/Gate service.
 * 
 * Resilience features:
 * - Lazy channel creation with double-checked locking
 * - Automatic channel reset on UNAVAILABLE errors
 * - Continuous config polling with error tolerance
 */
public class GateClient implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(GateClient.class);

    static {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    private final SatiConfig config;
    private final AtomicReference<ManagedChannel> channelRef = new AtomicReference<>();
    private final ReentrantLock channelLock = new ReentrantLock();
    private final ScheduledExecutorService configPoller;
    private final ObjectMapper objectMapper;

    private Consumer<BackendConfig> configListener;
    private BackendConfig lastConfig;
    private volatile String orgName;
    private volatile String configName;
    private volatile String certExpiration;

    /**
     * Backend configuration received from Gate.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class BackendConfig {
        @JsonProperty("database_url")
        public String databaseUrl;

        @JsonProperty("database_username")
        public String databaseUsername;

        @JsonProperty("database_password")
        public String databasePassword;

        @JsonProperty("database_host")
        public String databaseHost;

        @JsonProperty("database_port")
        public String databasePort;

        @JsonProperty("database_name")
        public String databaseName;

        @JsonProperty("database_type")
        public String databaseType;

        @JsonProperty("use_tls")
        public Boolean useTls;

        @JsonProperty("max_number_connections")
        public Integer maxConnections;

        @JsonProperty("trust_store_cert")
        public String trustStoreCert;

        @JsonProperty("key_store_cert")
        public String keyStoreCert;

        /**
         * Returns the explicit database URL if configured.
         * If not set, returns null - private implementations should build
         * the URL from the component parts (host, port, name, type).
         */
        public String getEffectiveJdbcUrl() {
            if (databaseUrl != null && !databaseUrl.isBlank()) {
                return databaseUrl;
            }
            return null;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o)
                return true;
            if (o == null || getClass() != o.getClass())
                return false;
            BackendConfig that = (BackendConfig) o;
            return java.util.Objects.equals(databaseUrl, that.databaseUrl) &&
                    java.util.Objects.equals(databaseUsername, that.databaseUsername) &&
                    java.util.Objects.equals(databasePassword, that.databasePassword) &&
                    java.util.Objects.equals(databaseHost, that.databaseHost) &&
                    java.util.Objects.equals(databasePort, that.databasePort) &&
                    java.util.Objects.equals(databaseName, that.databaseName);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(databaseUrl, databaseUsername, databasePassword,
                    databaseHost, databasePort, databaseName);
        }

        @Override
        public String toString() {
            return String.format("BackendConfig[url=%s, host=%s, port=%s, db=%s, type=%s, tls=%s]",
                    databaseUrl != null ? "***SET***" : getEffectiveJdbcUrl(),
                    databaseHost, databasePort, databaseName, databaseType, useTls);
        }
    }

    public GateClient(SatiConfig config) {
        this.config = config;
        this.objectMapper = new ObjectMapper();
        this.configPoller = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "gate-config-poller");
            t.setDaemon(true);
            return t;
        });

        // Extract cert expiration
        try {
            java.security.cert.CertificateFactory cf = java.security.cert.CertificateFactory.getInstance("X.509");
            var cert = (java.security.cert.X509Certificate) cf.generateCertificate(
                    new ByteArrayInputStream(config.publicCert().getBytes()));
            this.certExpiration = cert.getNotAfter().toString();
        } catch (Exception e) {
            this.certExpiration = "Unknown";
        }

        // Create initial channel
        getOrCreateChannel();
    }

    /**
     * Get or create the gRPC channel with double-checked locking.
     * Automatically recreates if the channel is dead.
     */
    private ManagedChannel getOrCreateChannel() {
        ManagedChannel channel = channelRef.get();

        // Fast path: channel exists and is healthy
        if (channel != null && !channel.isShutdown() && !channel.isTerminated()) {
            return channel;
        }

        // Slow path: need to create/recreate channel
        channelLock.lock();
        try {
            // Double-check inside lock
            channel = channelRef.get();
            if (channel != null && !channel.isShutdown() && !channel.isTerminated()) {
                return channel;
            }

            // Create new channel
            log.info("Creating gRPC channel to {}:{}", config.apiHostname(), config.apiPort());
            channel = createChannel();
            channelRef.set(channel);
            return channel;

        } finally {
            channelLock.unlock();
        }
    }

    private ManagedChannel createChannel() {
        if (!config.isGateConfigured()) {
            throw new IllegalStateException("GateClient: SatiConfig is missing required Gate fields.");
        }

        try {
            var channelCredentials = TlsChannelCredentials.newBuilder()
                    .trustManager(new ByteArrayInputStream(config.rootCert().getBytes()))
                    .keyManager(
                            new ByteArrayInputStream(config.publicCert().getBytes()),
                            new ByteArrayInputStream(config.privateKey().getBytes()))
                    .build();

            return Grpc.newChannelBuilderForAddress(config.apiHostname(), config.apiPort(), channelCredentials)
                    .keepAliveTime(32, TimeUnit.SECONDS)
                    .keepAliveTimeout(30, TimeUnit.SECONDS)
                    .keepAliveWithoutCalls(true)
                    .idleTimeout(30, TimeUnit.MINUTES)
                    .overrideAuthority("exile-proxy")
                    .build();

        } catch (Exception e) {
            throw new RuntimeException("Failed to create GateClient gRPC channel", e);
        }
    }

    /**
     * Reset the channel after a connection failure.
     * Next call to getOrCreateChannel() will create a new one.
     */
    private void resetChannel() {
        log.warn("Resetting gRPC channel due to connection failure...");

        channelLock.lock();
        try {
            ManagedChannel oldChannel = channelRef.getAndSet(null);
            if (oldChannel != null && !oldChannel.isShutdown()) {
                oldChannel.shutdown();
                try {
                    if (!oldChannel.awaitTermination(5, TimeUnit.SECONDS)) {
                        oldChannel.shutdownNow();
                    }
                } catch (InterruptedException e) {
                    oldChannel.shutdownNow();
                    Thread.currentThread().interrupt();
                }
            }
        } finally {
            channelLock.unlock();
        }
    }

    /**
     * Handle gRPC errors - reset channel on UNAVAILABLE.
     * 
     * @return true if the error was handled (should retry)
     */
    private boolean handleGrpcError(StatusRuntimeException e) {
        if (e.getStatus().getCode() == Status.Code.UNAVAILABLE) {
            log.warn("Gate unavailable, resetting channel: {}", e.getMessage());
            resetChannel();
            return true;
        }
        return false;
    }

    public void setConfigListener(Consumer<BackendConfig> listener) {
        this.configListener = listener;
    }

    public void startConfigPolling() {
        log.info("Starting Gate configuration polling (every 10 seconds)...");

        // Initial poll immediately
        configPoller.execute(this::pollConfiguration);

        // Then poll every 10 seconds
        configPoller.scheduleAtFixedRate(this::pollConfiguration, 10, 10, TimeUnit.SECONDS);
    }

    private void pollConfiguration() {
        try {
            ManagedChannel channel = getOrCreateChannel();

            var stub = build.buf.gen.tcnapi.exile.gate.v2.GateServiceGrpc.newBlockingStub(channel)
                    .withDeadlineAfter(30, TimeUnit.SECONDS);

            var request = build.buf.gen.tcnapi.exile.gate.v2.GetClientConfigurationRequest.newBuilder().build();
            var response = stub.getClientConfiguration(request);

            // Log config identity once on first receive
            if (this.orgName == null) {
                log.info("Received config from Gate: orgId={}, orgName={}, configName={}",
                        response.getOrgId(), response.getOrgName(), response.getConfigName());
            }

            this.orgName = response.getOrgName();
            this.configName = response.getConfigName();

            String configPayload = response.getConfigPayload();
            if (configPayload != null && !configPayload.isBlank()) {
                BackendConfig newConfig = objectMapper.readValue(configPayload, BackendConfig.class);

                // Only notify if config changed
                if (!newConfig.equals(lastConfig)) {
                    log.info("Received new backend configuration from Gate: {}", newConfig);
                    lastConfig = newConfig;

                    if (configListener != null) {
                        configListener.accept(newConfig);
                    }
                }
            }

        } catch (StatusRuntimeException e) {
            if (e.getStatus().getCode() == Status.Code.NOT_FOUND) {
                log.debug("No configuration found in Gate (not yet configured)");
            } else if (handleGrpcError(e)) {
                log.info("Will retry config poll on next interval");
            } else {
                log.warn("Error polling Gate configuration: {}", e.getStatus());
            }
        } catch (Exception e) {
            log.error("Failed to poll Gate configuration", e);
        }
    }

    public ManagedChannel getChannel() {
        return getOrCreateChannel();
    }

    public boolean checkConnection() {
        try {
            log.info("Checking connection to Gate...");

            ManagedChannel channel = getOrCreateChannel();
            var stub = build.buf.gen.tcnapi.exile.gate.v2.GateServiceGrpc.newBlockingStub(channel)
                    .withDeadlineAfter(5, TimeUnit.SECONDS);

            var request = build.buf.gen.tcnapi.exile.gate.v2.GetOrganizationInfoRequest.newBuilder().build();
            var response = stub.getOrganizationInfo(request);

            log.info("Organization Info: {}", response);
            log.info("Connection check: SUCCESS");
            return true;

        } catch (StatusRuntimeException e) {
            log.warn("Connection check failed: {}", e.getStatus().getCode());
            if (handleGrpcError(e)) {
                // Channel was reset, might work on retry
            }
            return e.getStatus().getCode() != Status.Code.UNAVAILABLE;

        } catch (Exception e) {
            log.error("Connection check failed unexpectedly", e);
            return false;
        }
    }

    /**
     * Check if channel is currently active.
     */
    public boolean isChannelActive() {
        ManagedChannel channel = channelRef.get();
        return channel != null && !channel.isShutdown() && !channel.isTerminated();
    }

    /**
     * Reset gRPC's internal connect backoff on the channel.
     * Forces immediate DNS re-resolution instead of waiting for gRPC's
     * own exponential backoff timer. Call before each stream attempt.
     */
    public void resetConnectBackoff() {
        ManagedChannel channel = channelRef.get();
        if (channel != null && !channel.isShutdown() && !channel.isTerminated()) {
            channel.resetConnectBackoff();
        }
    }

    public SatiConfig getConfig() {
        return config;
    }

    public String getOrgName() {
        return orgName;
    }

    public String getConfigName() {
        return configName;
    }

    public String getCertExpiration() {
        return certExpiration;
    }

    // ========== gRPC Convenience Methods ==========

    /**
     * Get a blocking stub with a 30s deadline. All convenience methods use this.
     */
    private build.buf.gen.tcnapi.exile.gate.v2.GateServiceGrpc.GateServiceBlockingStub getStub() {
        return build.buf.gen.tcnapi.exile.gate.v2.GateServiceGrpc.newBlockingStub(getOrCreateChannel())
                .withDeadlineAfter(30, TimeUnit.SECONDS);
    }

    // --- Agent Operations ---

    public java.util.Iterator<build.buf.gen.tcnapi.exile.gate.v2.ListAgentsResponse> listAgents(
            build.buf.gen.tcnapi.exile.gate.v2.ListAgentsRequest request) {
        return getStub().listAgents(request);
    }

    public build.buf.gen.tcnapi.exile.gate.v2.UpsertAgentResponse upsertAgent(
            build.buf.gen.tcnapi.exile.gate.v2.UpsertAgentRequest request) {
        return getStub().upsertAgent(request);
    }

    public build.buf.gen.tcnapi.exile.gate.v2.DialResponse dial(
            build.buf.gen.tcnapi.exile.gate.v2.DialRequest request) {
        return getStub().dial(request);
    }

    public build.buf.gen.tcnapi.exile.gate.v2.GetRecordingStatusResponse getRecordingStatus(
            build.buf.gen.tcnapi.exile.gate.v2.GetRecordingStatusRequest request) {
        return getStub().getRecordingStatus(request);
    }

    public void startCallRecording(build.buf.gen.tcnapi.exile.gate.v2.StartCallRecordingRequest request) {
        getStub().startCallRecording(request);
    }

    public void stopCallRecording(build.buf.gen.tcnapi.exile.gate.v2.StopCallRecordingRequest request) {
        getStub().stopCallRecording(request);
    }

    public build.buf.gen.tcnapi.exile.gate.v2.GetAgentStatusResponse getAgentStatus(
            build.buf.gen.tcnapi.exile.gate.v2.GetAgentStatusRequest request) {
        return getStub().getAgentStatus(request);
    }

    public build.buf.gen.tcnapi.exile.gate.v2.UpdateAgentStatusResponse updateAgentStatus(
            build.buf.gen.tcnapi.exile.gate.v2.UpdateAgentStatusRequest request) {
        return getStub().updateAgentStatus(request);
    }

    public build.buf.gen.tcnapi.exile.gate.v2.ListHuntGroupPauseCodesResponse listHuntGroupPauseCodes(
            build.buf.gen.tcnapi.exile.gate.v2.ListHuntGroupPauseCodesRequest request) {
        return getStub().listHuntGroupPauseCodes(request);
    }

    public void putCallOnSimpleHold(build.buf.gen.tcnapi.exile.gate.v2.PutCallOnSimpleHoldRequest request) {
        getStub().putCallOnSimpleHold(request);
    }

    public void takeCallOffSimpleHold(build.buf.gen.tcnapi.exile.gate.v2.TakeCallOffSimpleHoldRequest request) {
        getStub().takeCallOffSimpleHold(request);
    }

    public void muteAgent(build.buf.gen.tcnapi.exile.gate.v2.MuteAgentRequest request) {
        getStub().muteAgent(request);
    }

    public void unmuteAgent(build.buf.gen.tcnapi.exile.gate.v2.UnmuteAgentRequest request) {
        getStub().unmuteAgent(request);
    }

    public void addAgentCallResponse(build.buf.gen.tcnapi.exile.gate.v2.AddAgentCallResponseRequest request) {
        getStub().addAgentCallResponse(request);
    }

    // --- Transfer Operations ---

    public void transfer(build.buf.gen.tcnapi.exile.gate.v2.TransferRequest request) {
        getStub().transfer(request);
    }

    public void holdTransferMemberCaller(
            build.buf.gen.tcnapi.exile.gate.v2.HoldTransferMemberCallerRequest request) {
        getStub().holdTransferMemberCaller(request);
    }

    public void unholdTransferMemberCaller(
            build.buf.gen.tcnapi.exile.gate.v2.UnholdTransferMemberCallerRequest request) {
        getStub().unholdTransferMemberCaller(request);
    }

    public void holdTransferMemberAgent(
            build.buf.gen.tcnapi.exile.gate.v2.HoldTransferMemberAgentRequest request) {
        getStub().holdTransferMemberAgent(request);
    }

    public void unholdTransferMemberAgent(
            build.buf.gen.tcnapi.exile.gate.v2.UnholdTransferMemberAgentRequest request) {
        getStub().unholdTransferMemberAgent(request);
    }

    // --- Scrub List Operations ---

    public build.buf.gen.tcnapi.exile.gate.v2.ListScrubListsResponse listScrubLists(
            build.buf.gen.tcnapi.exile.gate.v2.ListScrubListsRequest request) {
        return getStub().listScrubLists(request);
    }

    public void updateScrubListEntry(build.buf.gen.tcnapi.exile.gate.v2.UpdateScrubListEntryRequest request) {
        getStub().updateScrubListEntry(request);
    }

    public void removeScrubListEntries(build.buf.gen.tcnapi.exile.gate.v2.RemoveScrubListEntriesRequest request) {
        getStub().removeScrubListEntries(request);
    }

    // --- Skill Operations ---

    public build.buf.gen.tcnapi.exile.gate.v2.ListSkillsResponse listSkills(
            build.buf.gen.tcnapi.exile.gate.v2.ListSkillsRequest request) {
        return getStub().listSkills(request);
    }

    public build.buf.gen.tcnapi.exile.gate.v2.ListAgentSkillsResponse listAgentSkills(
            build.buf.gen.tcnapi.exile.gate.v2.ListAgentSkillsRequest request) {
        return getStub().listAgentSkills(request);
    }

    public void assignAgentSkill(build.buf.gen.tcnapi.exile.gate.v2.AssignAgentSkillRequest request) {
        getStub().assignAgentSkill(request);
    }

    public void unassignAgentSkill(build.buf.gen.tcnapi.exile.gate.v2.UnassignAgentSkillRequest request) {
        getStub().unassignAgentSkill(request);
    }

    // --- NCL Ruleset Operations ---

    public build.buf.gen.tcnapi.exile.gate.v2.ListNCLRulesetNamesResponse listNCLRulesetNames(
            build.buf.gen.tcnapi.exile.gate.v2.ListNCLRulesetNamesRequest request) {
        return getStub().listNCLRulesetNames(request);
    }

    // --- Voice Recording Operations ---

    public java.util.Iterator<build.buf.gen.tcnapi.exile.gate.v2.SearchVoiceRecordingsResponse> searchVoiceRecordings(
            build.buf.gen.tcnapi.exile.gate.v2.SearchVoiceRecordingsRequest request) {
        return getStub().searchVoiceRecordings(request);
    }

    public build.buf.gen.tcnapi.exile.gate.v2.GetVoiceRecordingDownloadLinkResponse getVoiceRecordingDownloadLink(
            build.buf.gen.tcnapi.exile.gate.v2.GetVoiceRecordingDownloadLinkRequest request) {
        return getStub().getVoiceRecordingDownloadLink(request);
    }

    public build.buf.gen.tcnapi.exile.gate.v2.ListSearchableRecordingFieldsResponse listSearchableRecordingFields(
            build.buf.gen.tcnapi.exile.gate.v2.ListSearchableRecordingFieldsRequest request) {
        return getStub().listSearchableRecordingFields(request);
    }

    public build.buf.gen.tcnapi.exile.gate.v2.CreateRecordingLabelResponse createRecordingLabel(
            build.buf.gen.tcnapi.exile.gate.v2.CreateRecordingLabelRequest request) {
        return getStub().createRecordingLabel(request);
    }

    // --- Journey Buffer Operations ---

    public build.buf.gen.tcnapi.exile.gate.v2.AddRecordToJourneyBufferResponse addRecordToJourneyBuffer(
            build.buf.gen.tcnapi.exile.gate.v2.AddRecordToJourneyBufferRequest request) {
        return getStub().addRecordToJourneyBuffer(request);
    }

    @Override
    public void close() {
        log.info("Shutting down GateClient...");

        configPoller.shutdown();
        try {
            if (!configPoller.awaitTermination(2, TimeUnit.SECONDS)) {
                configPoller.shutdownNow();
            }
        } catch (InterruptedException e) {
            configPoller.shutdownNow();
            Thread.currentThread().interrupt();
        }

        ManagedChannel channel = channelRef.getAndSet(null);
        if (channel != null && !channel.isShutdown()) {
            channel.shutdown();
            try {
                if (!channel.awaitTermination(5, TimeUnit.SECONDS)) {
                    log.warn("GateClient channel did not terminate gracefully, forcing shutdown");
                    channel.shutdownNow();
                }
            } catch (InterruptedException e) {
                channel.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }
}
