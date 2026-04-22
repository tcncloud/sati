package com.tcn.sati.core.tenant;

import com.tcn.sati.config.BackendType;
import com.tcn.sati.config.SatiConfig;
import com.tcn.sati.core.AdaptiveSnapshot;
import com.tcn.sati.infra.adaptive.AdaptiveCapacity;
import com.tcn.sati.infra.backend.TenantBackendClient;
import com.tcn.sati.infra.backend.rest.RestBackendClient;
import com.tcn.sati.infra.executor.PriorityExecutor;
import com.tcn.sati.infra.gate.GateClient;
import com.tcn.sati.infra.gate.WorkStreamClient;
import com.tcn.sati.infra.logging.GrpcLogShipper;
import com.tcn.sati.infra.logging.MemoryLogAppender;
import com.tcn.sati.infra.metrics.MetricsManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Encapsulates all resources for a single tenant.
 *
 * Each tenant has its own:
 * - GateClient (gRPC connection to Exile/Gate)
 * - WorkStreamClient (v3 unified bidirectional stream for jobs and events)
 * - TenantBackendClient (database/API connection)
 * - ScheduledExecutorService (for tenant-specific tasks)
 */
public class TenantContext implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(TenantContext.class);

    private final String tenantKey;
    private final SatiConfig config;
    private final BackendType backendType;
    private final TenantBackendClient customBackendClient;
    private final String appName;
    private final String appVersion;

    private GateClient gateClient;
    private TenantBackendClient backendClient;
    private WorkStreamClient workStreamClient;
    private ScheduledExecutorService scheduler;
    private MetricsManager metricsManager;
    private GrpcLogShipper logShipper;
    private PriorityExecutor priorityExecutor;
    private AdaptiveCapacity adaptive;

    // Optional priority executor config (set before start())
    private int priorityPoolSize = 0;
    private int priorityMaxLowDepth = 100;

    // Optional adaptive concurrency config (set before start())
    private boolean adaptiveEnabled = true;
    private int minConcurrency = 1;
    private int initialConcurrency = 10;
    private int maxConcurrency = 100;
    private java.time.Duration shutdownDrainTimeout = java.time.Duration.ofSeconds(30);
    private double tracingSamplingFraction = 0.0;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Instant createdAt = Instant.now();
    private final TenantCircuitBreaker circuitBreaker;
    private volatile Instant lastHealthCheck;

    /**
     * Constructor for factory-created backend (standard flow).
     */
    public TenantContext(String tenantKey, SatiConfig config, BackendType backendType) {
        this(tenantKey, config, backendType, null, null, null);
    }

    /**
     * Constructor for custom backend client (e.g., BackendClient from private
     * application).
     */
    public TenantContext(String tenantKey, SatiConfig config, TenantBackendClient customBackendClient) {
        this(tenantKey, config, null, customBackendClient, null, null);
    }

    /**
     * Full constructor with app metadata.
     */
    public TenantContext(String tenantKey, SatiConfig config, BackendType backendType,
            TenantBackendClient customBackendClient, String appName, String appVersion) {
        this.tenantKey = tenantKey;
        this.config = config;
        this.backendType = backendType;
        this.customBackendClient = customBackendClient;
        this.appName = appName;
        this.appVersion = appVersion;
        this.circuitBreaker = new TenantCircuitBreaker(tenantKey);
    }

    /**
     * Start all tenant resources.
     */
    public void start() {
        if (!running.compareAndSet(false, true)) {
            log.warn("Tenant {} already running", tenantKey);
            return;
        }

        log.info("Starting tenant: {}", tenantKey);

        try {
            // 1. Create scheduler for tenant-specific tasks
            this.scheduler = Executors.newScheduledThreadPool(2, r -> {
                Thread t = new Thread(r, "tenant-" + tenantKey + "-scheduler");
                t.setDaemon(true);
                return t;
            });

            // 2. Initialize Gate Client
            if (config.isGateConfigured()) {
                this.gateClient = new GateClient(config);
            } else {
                log.warn("Tenant {}: Gate not configured - gRPC features disabled", tenantKey);
            }

            // 3. Initialize Backend Client
            if (customBackendClient != null) {
                this.backendClient = customBackendClient;
                log.info("Tenant {}: Using custom backend client", tenantKey);
            } else if (backendType == BackendType.REST) {
                this.backendClient = new RestBackendClient(config);
                log.info("Tenant {}: Using REST backend client", tenantKey);
            } else {
                throw new IllegalStateException(
                        "JDBC backend requires custom client injection via SatiApp.builder().backendClient()");
            }

            // 4. Initialize WorkStream (v3 unified bidirectional stream)
            if (gateClient != null) {
                this.workStreamClient = new WorkStreamClient(
                        gateClient, backendClient, appName, appVersion, maxConcurrency);
                workStreamClient.setShutdownDrainTimeout(shutdownDrainTimeout);
                workStreamClient.start();
                log.info("Tenant {}: WorkStream started", tenantKey);
            }

            // 5. Initialize telemetry (metrics + log shipping)
            if (gateClient != null && workStreamClient != null) {
                String clientId = tenantKey + "-" + UUID.randomUUID().toString().substring(0, 8);
                String orgId = config.org() != null ? config.org() : tenantKey;
                String certName = tenantKey;
                try {
                    this.metricsManager = new MetricsManager(gateClient, clientId, orgId, certName,
                            workStreamClient, tracingSamplingFraction);
                    workStreamClient.setDurationRecorder(metricsManager::recordWorkDuration);
                    workStreamClient.setReconnectRecorder(metricsManager::recordReconnectDuration);
                    workStreamClient.setMethodRecorder(metricsManager::recordMethodCall);

                    // Create PriorityExecutor if configured (needs meter from MetricsManager)
                    if (priorityPoolSize > 0) {
                        this.priorityExecutor = new PriorityExecutor(
                                priorityPoolSize, priorityMaxLowDepth, metricsManager.meter());
                        workStreamClient.setPriorityExecutor(priorityExecutor);
                        log.info("Tenant {}: PriorityExecutor started (pool={}, maxLow={})",
                                tenantKey, priorityPoolSize, priorityMaxLowDepth);
                    }

                    // Create AdaptiveCapacity if enabled
                    if (adaptiveEnabled) {
                        final TenantBackendClient bc = backendClient;
                        this.adaptive = new AdaptiveCapacity(minConcurrency, initialConcurrency, maxConcurrency,
                                bc::resourceLimits);
                        workStreamClient.setAdaptiveCapacity(adaptive);
                        log.info("Tenant {}: AdaptiveCapacity started (min={}, initial={}, max={})",
                                tenantKey, minConcurrency, initialConcurrency, maxConcurrency);
                    }
                } catch (Exception e) {
                    log.warn("Tenant {}: Failed to initialize MetricsManager: {}", tenantKey, e.getMessage());
                }
                try {
                    this.logShipper = new GrpcLogShipper(gateClient, clientId);
                    MemoryLogAppender.enableLogShipper(logShipper);
                } catch (Exception e) {
                    log.warn("Tenant {}: Failed to initialize log shipping: {}", tenantKey, e.getMessage());
                }
            }

            log.info("Tenant {} started successfully", tenantKey);

        } catch (Exception e) {
            log.error("Failed to start tenant {}", tenantKey, e);
            running.set(false);
            close(); // Clean up any partial resources
            throw new RuntimeException("Failed to start tenant: " + tenantKey, e);
        }
    }

    /**
     * Check if tenant is healthy.
     */
    public boolean isHealthy() {
        lastHealthCheck = Instant.now();

        if (!running.get())
            return false;

        boolean healthy = true;
        if (gateClient != null && !gateClient.isChannelActive())
            healthy = false;
        if (backendClient != null && !backendClient.isConnected())
            healthy = false;

        if (healthy) {
            circuitBreaker.recordSuccess();
        } else {
            circuitBreaker.recordFailure(new RuntimeException("Health check failed"));
        }

        return healthy && circuitBreaker.canExecute();
    }

    /**
     * Check if circuit breaker allows work to be routed to this tenant.
     */
    public boolean canAcceptWork() {
        return running.get() && circuitBreaker.canExecute();
    }

    public TenantCircuitBreaker getCircuitBreaker() {
        return circuitBreaker;
    }

    /** Configure priority execution. Must be called before start(). */
    public void setPriorityExecutorConfig(int poolSize, int maxLowDepth) {
        this.priorityPoolSize = poolSize;
        this.priorityMaxLowDepth = maxLowDepth;
    }

    public PriorityExecutor getPriorityExecutor() {
        return priorityExecutor;
    }

    /** Configure adaptive concurrency. Must be called before start(). */
    public void setAdaptiveConfig(boolean enabled, int min, int initial, int max) {
        this.adaptiveEnabled = enabled;
        this.minConcurrency = min;
        this.initialConcurrency = initial;
        this.maxConcurrency = max;
    }

    /** Configure graceful drain timeout. Must be called before start(). */
    public void setShutdownDrainTimeout(java.time.Duration timeout) {
        this.shutdownDrainTimeout = timeout;
    }

    /** Configure GCP Cloud Trace sampling fraction (0.0 = disabled). Must be called before start(). */
    public void setTracingSamplingFraction(double fraction) {
        this.tracingSamplingFraction = fraction;
    }

    /** Get the current adaptive concurrency snapshot (empty if adaptive is disabled or not yet started). */
    public java.util.Optional<AdaptiveSnapshot> adaptiveSnapshot() {
        var a = adaptive;
        if (a == null) return java.util.Optional.empty();
        return java.util.Optional.of(new AdaptiveSnapshot(
                a.limit(), a.minLimit(), a.maxLimit(), a.effectiveCeiling(),
                a.jobP95Nanos(), a.jobEmaNanos(), a.decayingMinNanos(),
                a.lastSloGradient(), a.lastMinGradient(), a.lastResourceGradient(),
                a.errorCount(), a.sampleCount()));
    }

    // ========== Getters ==========

    public String getTenantKey() {
        return tenantKey;
    }

    public GateClient getGateClient() {
        return gateClient;
    }

    public TenantBackendClient getBackendClient() {
        return backendClient;
    }

    public WorkStreamClient getWorkStreamClient() {
        return workStreamClient;
    }

    public boolean isRunning() {
        return running.get();
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getLastHealthCheck() {
        return lastHealthCheck;
    }

    /**
     * Get tenant status for monitoring.
     */
    public TenantStatus getStatus() {
        return new TenantStatus(
                tenantKey,
                running.get(),
                gateClient != null && gateClient.isChannelActive(),
                backendClient != null && backendClient.isConnected(),
                workStreamClient != null && workStreamClient.isConnected(),
                workStreamClient != null ? workStreamClient.getCompletedTotal() : 0,
                workStreamClient != null ? workStreamClient.getFailedTotal() : 0,
                createdAt.toString());
    }

    public record TenantStatus(
            String tenantKey,
            boolean running,
            boolean gateConnected,
            boolean backendConnected,
            boolean workStreamConnected,
            long completedWork,
            long failedWork,
            String createdAt) {
    }

    /**
     * Reconnect Gate/gRPC with new configuration (e.g., after certificate
     * rotation).
     * Does NOT touch HTTP server or backend connections.
     */
    public synchronized void reconnectGate(SatiConfig newConfig) {
        log.info("Tenant {}: Reconnecting Gate...", tenantKey);

        // Close existing Gate resources
        if (workStreamClient != null) {
            try {
                workStreamClient.close();
            } catch (Exception e) {
                log.warn("Error closing work stream", e);
            }
            workStreamClient = null;
        }
        if (gateClient != null) {
            try {
                gateClient.close();
            } catch (Exception e) {
                log.warn("Error closing gate client", e);
            }
            gateClient = null;
        }

        // Recreate with new config
        if (newConfig.isGateConfigured()) {
            this.gateClient = new GateClient(newConfig);

            // Re-wire config listener so backend gets DB credentials from new Gate
            if (backendClient instanceof com.tcn.sati.infra.backend.jdbc.JdbcBackendClient jdbcClient) {
                gateClient.setConfigListener(jdbcClient::onBackendConfigReceived);
            }

            // Restart config polling (populates org name, config name, etc.)
            gateClient.startConfigPolling();

            // Recreate WorkStream
            if (backendClient != null) {
                this.workStreamClient = new WorkStreamClient(
                        gateClient, backendClient, appName, appVersion, maxConcurrency);
                workStreamClient.setShutdownDrainTimeout(shutdownDrainTimeout);

                // Re-wire metric recorders and PriorityExecutor if they survived the reconnect
                if (metricsManager != null) {
                    workStreamClient.setDurationRecorder(metricsManager::recordWorkDuration);
                    workStreamClient.setReconnectRecorder(metricsManager::recordReconnectDuration);
                    workStreamClient.setMethodRecorder(metricsManager::recordMethodCall);
                }
                if (priorityExecutor != null) {
                    workStreamClient.setPriorityExecutor(priorityExecutor);
                }
                if (adaptive != null) {
                    workStreamClient.setAdaptiveCapacity(adaptive);
                }

                workStreamClient.start();
            }

            log.info("Tenant {}: Gate reconnected successfully", tenantKey);
        }
    }

    @Override
    public void close() {
        log.info("Shutting down tenant: {}", tenantKey);
        running.set(false);

        MemoryLogAppender.disableLogShipper();

        // Drain WorkStream first — blocks until in-flight work completes (up to drain timeout).
        // PriorityExecutor must stay alive during drain since it processes the in-flight items.
        if (workStreamClient != null) {
            try {
                workStreamClient.close();
            } catch (Exception e) {
                log.warn("Error closing work stream for {}", tenantKey, e);
            }
        }

        // Now safe to shut down PriorityExecutor — no new work will be submitted.
        if (priorityExecutor != null) {
            priorityExecutor.shutdown();
        }

        if (metricsManager != null) {
            try {
                metricsManager.close();
            } catch (Exception e) {
                log.warn("Error closing metrics manager for {}", tenantKey, e);
            }
        }

        if (gateClient != null) {
            try {
                gateClient.close();
            } catch (Exception e) {
                log.warn("Error closing gate client for {}", tenantKey, e);
            }
        }

        if (backendClient != null) {
            try {
                backendClient.close();
            } catch (Exception e) {
                log.warn("Error closing backend client for {}", tenantKey, e);
            }
        }

        if (scheduler != null) {
            scheduler.shutdownNow();
        }

        log.info("Tenant {} shutdown complete", tenantKey);
    }
}
