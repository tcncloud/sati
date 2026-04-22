package com.tcn.sati.infra.gate;

import build.buf.gen.tcnapi.exile.gate.v3.*;
import com.google.protobuf.Timestamp;
import com.tcn.sati.config.SatiConfig;
import io.grpc.Grpc;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.TlsChannelCredentials;
import io.opentelemetry.sdk.metrics.data.MetricData;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.security.Security;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * gRPC client for connecting to Exile/Gate service (v3 API).
 *
 * Hosts one shared ManagedChannel and creates per-service blocking stubs on demand.
 * All v3 Gate services (AgentService, CallService, ConfigService, RecordingService,
 * ScrubListService, JourneyService) share the same channel.
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

    private Consumer<BackendConfig> configListener;
    private BackendConfig lastConfig;
    private volatile String orgName;
    private volatile String configName;
    private volatile String certExpiration;

    /**
     * Backend configuration received from Gate config payload (Struct).
     */
    public static class BackendConfig {
        public String databaseUrl;
        public String databaseUsername;
        public String databasePassword;
        public String databaseHost;
        public String databasePort;
        public String databaseName;
        public String databaseType;
        public Boolean useTls;
        public Integer maxConnections;
        public Integer maxPriorityJobs;
        public Integer maxBulkJobs;
        public String trustStoreCert;
        public String keyStoreCert;

        public String getEffectiveJdbcUrl() {
            if (databaseUrl != null && !databaseUrl.isBlank()) return databaseUrl;
            return null;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
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
        this.configPoller = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "gate-config-poller");
            t.setDaemon(true);
            return t;
        });

        try {
            java.security.cert.CertificateFactory cf = java.security.cert.CertificateFactory.getInstance("X.509");
            var cert = (java.security.cert.X509Certificate) cf.generateCertificate(
                    new ByteArrayInputStream(config.publicCert().getBytes()));
            this.certExpiration = cert.getNotAfter().toString();
        } catch (Exception e) {
            this.certExpiration = "Unknown";
        }

        getOrCreateChannel();
    }

    // ========== Channel management ==========

    private ManagedChannel getOrCreateChannel() {
        ManagedChannel channel = channelRef.get();
        if (channel != null && !channel.isShutdown() && !channel.isTerminated()) return channel;

        channelLock.lock();
        try {
            channel = channelRef.get();
            if (channel != null && !channel.isShutdown() && !channel.isTerminated()) return channel;
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

    private void resetChannel() {
        channelLock.lock();
        try {
            ManagedChannel old = channelRef.getAndSet(null);
            if (old != null && !old.isShutdown()) {
                old.shutdown();
                try {
                    if (!old.awaitTermination(5, TimeUnit.SECONDS)) old.shutdownNow();
                } catch (InterruptedException e) {
                    old.shutdownNow();
                    Thread.currentThread().interrupt();
                }
            }
        } finally {
            channelLock.unlock();
        }
    }

    private boolean handleGrpcError(StatusRuntimeException e) {
        if (e.getStatus().getCode() == Status.Code.UNAVAILABLE) {
            log.warn("Gate unavailable, resetting channel: {}", e.getMessage());
            resetChannel();
            return true;
        }
        return false;
    }

    public ManagedChannel getChannel() {
        return getOrCreateChannel();
    }

    public void resetConnectBackoff() {
        ManagedChannel channel = channelRef.get();
        if (channel != null && !channel.isShutdown() && !channel.isTerminated()) {
            channel.resetConnectBackoff();
        }
    }

    public boolean isChannelActive() {
        ManagedChannel channel = channelRef.get();
        return channel != null && !channel.isShutdown() && !channel.isTerminated();
    }

    // ========== Per-service stubs ==========

    private AgentServiceGrpc.AgentServiceBlockingStub agentStub() {
        return AgentServiceGrpc.newBlockingStub(getOrCreateChannel())
                .withDeadlineAfter(30, TimeUnit.SECONDS);
    }

    private CallServiceGrpc.CallServiceBlockingStub callStub() {
        return CallServiceGrpc.newBlockingStub(getOrCreateChannel())
                .withDeadlineAfter(30, TimeUnit.SECONDS);
    }

    private ConfigServiceGrpc.ConfigServiceBlockingStub configStub() {
        return ConfigServiceGrpc.newBlockingStub(getOrCreateChannel())
                .withDeadlineAfter(30, TimeUnit.SECONDS);
    }

    private RecordingServiceGrpc.RecordingServiceBlockingStub recStub() {
        return RecordingServiceGrpc.newBlockingStub(getOrCreateChannel())
                .withDeadlineAfter(30, TimeUnit.SECONDS);
    }

    private ScrubListServiceGrpc.ScrubListServiceBlockingStub scrubStub() {
        return ScrubListServiceGrpc.newBlockingStub(getOrCreateChannel())
                .withDeadlineAfter(30, TimeUnit.SECONDS);
    }

    private JourneyServiceGrpc.JourneyServiceBlockingStub journeyStub() {
        return JourneyServiceGrpc.newBlockingStub(getOrCreateChannel())
                .withDeadlineAfter(30, TimeUnit.SECONDS);
    }

    private TelemetryServiceGrpc.TelemetryServiceBlockingStub telemetryStub() {
        return TelemetryServiceGrpc.newBlockingStub(getOrCreateChannel())
                .withDeadlineAfter(30, TimeUnit.SECONDS);
    }

    // ========== Telemetry ==========

    /** Send structured log records to Gate. Returns accepted count. */
    public int reportLogs(String clientId, List<LogRecord> records) {
        var builder = ReportLogsRequest.newBuilder().setClientId(clientId).addAllRecords(records);
        var resp = telemetryStub().reportLogs(builder.build());
        return resp.getAcceptedCount();
    }

    /** Send OTel metric data to Gate. Returns accepted count. */
    public int reportMetrics(String clientId, Collection<MetricData> metrics) {
        var now = Instant.now();
        var builder = ReportMetricsRequest.newBuilder()
                .setClientId(clientId)
                .setCollectionTime(toTimestamp(now));

        for (var metric : metrics) {
            var name = metric.getName();
            var description = metric.getDescription();
            var unit = metric.getUnit();
            var resourceAttrs = attributesToMap(metric.getResource().getAttributes());

            switch (metric.getType()) {
                case LONG_GAUGE -> {
                    for (var point : metric.getLongGaugeData().getPoints()) {
                        var attrs = mergeAttributes(resourceAttrs, point.getAttributes());
                        builder.addDataPoints(MetricDataPoint.newBuilder()
                                .setName(name).setDescription(description).setUnit(unit)
                                .setType(MetricType.METRIC_TYPE_GAUGE).putAllAttributes(attrs)
                                .setTime(toTimestamp(point.getEpochNanos()))
                                .setDoubleValue(point.getValue()).build());
                    }
                }
                case DOUBLE_GAUGE -> {
                    for (var point : metric.getDoubleGaugeData().getPoints()) {
                        var attrs = mergeAttributes(resourceAttrs, point.getAttributes());
                        builder.addDataPoints(MetricDataPoint.newBuilder()
                                .setName(name).setDescription(description).setUnit(unit)
                                .setType(MetricType.METRIC_TYPE_GAUGE).putAllAttributes(attrs)
                                .setTime(toTimestamp(point.getEpochNanos()))
                                .setDoubleValue(point.getValue()).build());
                    }
                }
                case LONG_SUM -> {
                    for (var point : metric.getLongSumData().getPoints()) {
                        var attrs = mergeAttributes(resourceAttrs, point.getAttributes());
                        builder.addDataPoints(MetricDataPoint.newBuilder()
                                .setName(name).setDescription(description).setUnit(unit)
                                .setType(MetricType.METRIC_TYPE_SUM).putAllAttributes(attrs)
                                .setTime(toTimestamp(point.getEpochNanos()))
                                .setIntValue(point.getValue()).build());
                    }
                }
                case DOUBLE_SUM -> {
                    for (var point : metric.getDoubleSumData().getPoints()) {
                        var attrs = mergeAttributes(resourceAttrs, point.getAttributes());
                        builder.addDataPoints(MetricDataPoint.newBuilder()
                                .setName(name).setDescription(description).setUnit(unit)
                                .setType(MetricType.METRIC_TYPE_SUM).putAllAttributes(attrs)
                                .setTime(toTimestamp(point.getEpochNanos()))
                                .setDoubleValue(point.getValue()).build());
                    }
                }
                case HISTOGRAM -> {
                    for (var point : metric.getHistogramData().getPoints()) {
                        var attrs = mergeAttributes(resourceAttrs, point.getAttributes());
                        var hv = HistogramValue.newBuilder()
                                .setCount(point.getCount()).setSum(point.getSum())
                                .addAllBoundaries(point.getBoundaries())
                                .addAllBucketCounts(point.getCounts())
                                .setMin(point.getMin()).setMax(point.getMax());
                        builder.addDataPoints(MetricDataPoint.newBuilder()
                                .setName(name).setDescription(description).setUnit(unit)
                                .setType(MetricType.METRIC_TYPE_HISTOGRAM).putAllAttributes(attrs)
                                .setTime(toTimestamp(point.getEpochNanos()))
                                .setHistogramValue(hv).build());
                    }
                }
                default -> {} // skip unsupported types
            }
        }

        var resp = telemetryStub().reportMetrics(builder.build());
        return resp.getAcceptedCount();
    }

    private static Map<String, String> attributesToMap(io.opentelemetry.api.common.Attributes attrs) {
        var map = new HashMap<String, String>();
        attrs.forEach((k, v) -> map.put(k.getKey(), String.valueOf(v)));
        return map;
    }

    private static Map<String, String> mergeAttributes(Map<String, String> resourceAttrs,
            io.opentelemetry.api.common.Attributes pointAttrs) {
        var merged = new HashMap<>(resourceAttrs);
        pointAttrs.forEach((k, v) -> merged.put(k.getKey(), String.valueOf(v)));
        return merged;
    }

    private static Timestamp toTimestamp(Instant instant) {
        return Timestamp.newBuilder()
                .setSeconds(instant.getEpochSecond())
                .setNanos(instant.getNano())
                .build();
    }

    private static Timestamp toTimestamp(long epochNanos) {
        return Timestamp.newBuilder()
                .setSeconds(epochNanos / 1_000_000_000L)
                .setNanos((int) (epochNanos % 1_000_000_000L))
                .build();
    }

    // ========== Config polling ==========

    public void setConfigListener(Consumer<BackendConfig> listener) {
        this.configListener = listener;
    }

    public void startConfigPolling() {
        log.info("Starting Gate configuration polling (every 10 seconds)...");
        configPoller.execute(this::pollConfiguration);
        configPoller.scheduleAtFixedRate(this::pollConfiguration, 10, 10, TimeUnit.SECONDS);
    }

    private void pollConfiguration() {
        try {
            var response = configStub().getClientConfiguration(GetClientConfigurationRequest.newBuilder().build());

            if (this.orgName == null) {
                log.info("Received config from Gate: orgId={}, orgName={}, configName={}",
                        response.getOrgId(), response.getOrgName(), response.getConfigName());
            }
            this.orgName = response.getOrgName();
            this.configName = response.getConfigName();

            var struct = response.getConfigPayload();
            if (struct != null && !struct.getFieldsMap().isEmpty()) {
                BackendConfig newConfig = structToBackendConfig(struct);
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

    private static BackendConfig structToBackendConfig(com.google.protobuf.Struct struct) {
        var cfg = new BackendConfig();
        var fields = struct.getFieldsMap();
        cfg.databaseUrl = getString(fields, "database_url");
        cfg.databaseUsername = getString(fields, "database_username");
        cfg.databasePassword = getString(fields, "database_password");
        cfg.databaseHost = getString(fields, "database_host");
        cfg.databasePort = getString(fields, "database_port");
        cfg.databaseName = getString(fields, "database_name");
        cfg.databaseType = getString(fields, "database_type");
        cfg.trustStoreCert = getString(fields, "trust_store_cert");
        cfg.keyStoreCert = getString(fields, "key_store_cert");
        var tlsVal = fields.get("use_tls");
        if (tlsVal != null && tlsVal.hasBoolValue()) cfg.useTls = tlsVal.getBoolValue();
        var maxConVal = fields.get("max_number_connections");
        if (maxConVal != null && maxConVal.hasNumberValue()) cfg.maxConnections = (int) maxConVal.getNumberValue();
        var priVal = fields.get("max_priority_jobs");
        if (priVal != null && priVal.hasNumberValue()) cfg.maxPriorityJobs = (int) priVal.getNumberValue();
        var bulkVal = fields.get("max_bulk_jobs");
        if (bulkVal != null && bulkVal.hasNumberValue()) cfg.maxBulkJobs = (int) bulkVal.getNumberValue();
        return cfg;
    }

    private static String getString(java.util.Map<String, com.google.protobuf.Value> fields, String key) {
        var v = fields.get(key);
        if (v == null) return null;
        if (v.hasStringValue()) return v.getStringValue().isBlank() ? null : v.getStringValue();
        return null;
    }

    public boolean checkConnection() {
        try {
            var response = configStub().getOrganizationInfo(GetOrganizationInfoRequest.newBuilder().build());
            log.info("Organization Info: orgId={}, orgName={}", response.getOrgId(), response.getOrgName());
            log.info("Connection check: SUCCESS");
            return true;
        } catch (StatusRuntimeException e) {
            log.warn("Connection check failed: {}", e.getStatus().getCode());
            if (handleGrpcError(e)) {}
            return e.getStatus().getCode() != Status.Code.UNAVAILABLE;
        } catch (Exception e) {
            log.error("Connection check failed unexpectedly", e);
            return false;
        }
    }

    // ========== Agent operations ==========

    public ListAgentsResponse listAgents(ListAgentsRequest request) {
        return agentStub().listAgents(request);
    }

    public UpsertAgentResponse upsertAgent(UpsertAgentRequest request) {
        return agentStub().upsertAgent(request);
    }

    public void setAgentCredentials(SetAgentCredentialsRequest request) {
        agentStub().setAgentCredentials(request);
    }

    public GetAgentStatusResponse getAgentStatus(GetAgentStatusRequest request) {
        return agentStub().getAgentStatus(request);
    }

    public void updateAgentStatus(UpdateAgentStatusRequest request) {
        agentStub().updateAgentStatus(request);
    }

    public ListHuntGroupPauseCodesResponse listHuntGroupPauseCodes(ListHuntGroupPauseCodesRequest request) {
        return agentStub().listHuntGroupPauseCodes(request);
    }

    public void muteAgent(MuteAgentRequest request) {
        agentStub().muteAgent(request);
    }

    public void unmuteAgent(UnmuteAgentRequest request) {
        agentStub().unmuteAgent(request);
    }

    public void addAgentCallResponse(AddAgentCallResponseRequest request) {
        agentStub().addAgentCallResponse(request);
    }

    public ListSkillsResponse listSkills(ListSkillsRequest request) {
        return agentStub().listSkills(request);
    }

    public ListAgentSkillsResponse listAgentSkills(ListAgentSkillsRequest request) {
        return agentStub().listAgentSkills(request);
    }

    public void assignAgentSkill(AssignAgentSkillRequest request) {
        agentStub().assignAgentSkill(request);
    }

    public void unassignAgentSkill(UnassignAgentSkillRequest request) {
        agentStub().unassignAgentSkill(request);
    }

    // ========== Call operations ==========

    public DialResponse dial(DialRequest request) {
        return callStub().dial(request);
    }

    public void transfer(TransferRequest request) {
        callStub().transfer(request);
    }

    public void setHoldState(SetHoldStateRequest request) {
        callStub().setHoldState(request);
    }

    public void startCallRecording(StartCallRecordingRequest request) {
        callStub().startCallRecording(request);
    }

    public void stopCallRecording(StopCallRecordingRequest request) {
        callStub().stopCallRecording(request);
    }

    public GetRecordingStatusResponse getRecordingStatus(GetRecordingStatusRequest request) {
        return callStub().getRecordingStatus(request);
    }

    public ListComplianceRulesetsResponse listComplianceRulesets(ListComplianceRulesetsRequest request) {
        return callStub().listComplianceRulesets(request);
    }

    // ========== Recording operations ==========

    public SearchVoiceRecordingsResponse searchVoiceRecordings(SearchVoiceRecordingsRequest request) {
        return recStub().searchVoiceRecordings(request);
    }

    public GetDownloadLinkResponse getDownloadLink(GetDownloadLinkRequest request) {
        return recStub().getDownloadLink(request);
    }

    public ListSearchableFieldsResponse listSearchableFields(ListSearchableFieldsRequest request) {
        return recStub().listSearchableFields(request);
    }

    public CreateLabelResponse createLabel(CreateLabelRequest request) {
        return recStub().createLabel(request);
    }

    // ========== Scrub list operations ==========

    public ListScrubListsResponse listScrubLists(ListScrubListsRequest request) {
        return scrubStub().listScrubLists(request);
    }

    public void updateEntry(UpdateEntryRequest request) {
        scrubStub().updateEntry(request);
    }

    public void removeEntries(RemoveEntriesRequest request) {
        scrubStub().removeEntries(request);
    }

    // ========== Journey buffer ==========

    public AddRecordToJourneyBufferResponse addRecordToJourneyBuffer(AddRecordToJourneyBufferRequest request) {
        return journeyStub().addRecordToJourneyBuffer(request);
    }

    // ========== Metadata ==========

    public SatiConfig getConfig() { return config; }
    public String getOrgName() { return orgName; }
    public String getConfigName() { return configName; }
    public String getCertExpiration() { return certExpiration; }

    @Override
    public void close() {
        log.info("Shutting down GateClient...");
        configPoller.shutdown();
        try {
            if (!configPoller.awaitTermination(2, TimeUnit.SECONDS)) configPoller.shutdownNow();
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
