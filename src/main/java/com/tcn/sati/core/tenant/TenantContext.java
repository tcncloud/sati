package com.tcn.sati.core.tenant;

import com.tcn.sati.config.BackendType;
import com.tcn.sati.config.SatiConfig;
import com.tcn.sati.core.job.JobProcessor;
import com.tcn.sati.infra.backend.TenantBackendClient;
import com.tcn.sati.infra.backend.rest.RestBackendClient;
import com.tcn.sati.infra.gate.EventStreamClient;
import com.tcn.sati.infra.gate.GateClient;
import com.tcn.sati.infra.gate.JobQueueClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Encapsulates all resources for a single tenant.
 * 
 * Each tenant has its own:
 * - GateClient (gRPC connection to Exile/Gate)
 * - JobQueueClient (receives jobs from Gate with ACK support)
 * - EventStreamClient (receives events from Gate with ACK support)
 * - JobProcessor (executes jobs)
 * - TenantBackendClient (database/API connection)
 * - ScheduledExecutorService (for tenant-specific tasks)
 */
public class TenantContext implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(TenantContext.class);

    private final String tenantKey;
    private final SatiConfig config;
    private final BackendType backendType;
    private final TenantBackendClient customBackendClient;

    private GateClient gateClient;
    private TenantBackendClient backendClient;
    private JobProcessor jobProcessor;
    private JobQueueClient jobQueueClient;
    private EventStreamClient eventStreamClient;
    private ScheduledExecutorService scheduler;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Instant createdAt = Instant.now();
    private volatile Instant lastHealthCheck;

    /**
     * Constructor for factory-created backend (standard flow).
     */
    public TenantContext(String tenantKey, SatiConfig config, BackendType backendType) {
        this.tenantKey = tenantKey;
        this.config = config;
        this.backendType = backendType;
        this.customBackendClient = null;
    }

    /**
     * Constructor for custom backend client (e.g., BackendClient from private
     * application).
     */
    public TenantContext(String tenantKey, SatiConfig config, TenantBackendClient customBackendClient) {
        this.tenantKey = tenantKey;
        this.config = config;
        this.backendType = null; // Not used when custom client provided
        this.customBackendClient = customBackendClient;
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

            // 4. Note: For JDBC backends using custom clients,
            // gate config polling should be wired up in Main.java after start().

            // 5. Initialize Job and Event Processing (new APIs with ACK support)
            if (gateClient != null) {
                this.jobProcessor = new JobProcessor(backendClient, gateClient);

                // Job queue - bidirectional stream with acknowledgment
                this.jobQueueClient = new JobQueueClient(gateClient, job -> {
                    try {
                        jobProcessor.processJob(job);
                        return true; // ACK on success
                    } catch (Exception e) {
                        log.error("Job processing failed: {}", e.getMessage());
                        return false; // Don't ACK - will be redelivered
                    }
                });
                this.jobQueueClient.start();

                // Event stream - handles agent calls, telephony results, etc.
                this.eventStreamClient = new EventStreamClient(gateClient, backendClient);
                this.eventStreamClient.start();

                log.info("Tenant {}: Job queue and event stream started", tenantKey);
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
        if (gateClient != null && !gateClient.isChannelActive())
            return false;
        if (backendClient != null && !backendClient.isConnected())
            return false;

        return true;
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

    public JobProcessor getJobProcessor() {
        return jobProcessor;
    }

    public JobQueueClient getJobQueueClient() {
        return jobQueueClient;
    }

    public EventStreamClient getEventStreamClient() {
        return eventStreamClient;
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
                jobQueueClient != null && jobQueueClient.isConnected(),
                eventStreamClient != null && eventStreamClient.isRunning(),
                jobProcessor != null ? jobProcessor.getProcessedJobs() : 0,
                jobProcessor != null ? jobProcessor.getFailedJobs() : 0,
                eventStreamClient != null ? eventStreamClient.getEventsProcessed() : 0,
                createdAt);
    }

    public record TenantStatus(
            String tenantKey,
            boolean running,
            boolean gateConnected,
            boolean backendConnected,
            boolean jobQueueConnected,
            boolean eventStreamRunning,
            long processedJobs,
            long failedJobs,
            long processedEvents,
            Instant createdAt) {
    }

    /**
     * Reconnect Gate/gRPC with new configuration (e.g., after certificate
     * rotation).
     * Does NOT touch HTTP server or backend connections.
     */
    public synchronized void reconnectGate(SatiConfig newConfig) {
        log.info("Tenant {}: Reconnecting Gate...", tenantKey);

        // Close existing Gate resources
        if (eventStreamClient != null) {
            try {
                eventStreamClient.close();
            } catch (Exception e) {
                log.warn("Error closing event stream", e);
            }
            eventStreamClient = null;
        }
        if (jobQueueClient != null) {
            try {
                jobQueueClient.close();
            } catch (Exception e) {
                log.warn("Error closing job queue", e);
            }
            jobQueueClient = null;
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

            // Note: For custom backend clients,
            // config listener re-wiring should be done in Main.java

            // Recreate job queue and event stream
            if (jobProcessor != null && backendClient != null) {
                this.jobQueueClient = new JobQueueClient(gateClient, job -> {
                    try {
                        jobProcessor.processJob(job);
                        return true;
                    } catch (Exception e) {
                        log.error("Job processing failed: {}", e.getMessage());
                        return false;
                    }
                });
                this.jobQueueClient.start();

                this.eventStreamClient = new EventStreamClient(gateClient, backendClient);
                this.eventStreamClient.start();
            }

            log.info("Tenant {}: Gate reconnected successfully", tenantKey);
        }
    }

    @Override
    public void close() {
        log.info("Shutting down tenant: {}", tenantKey);
        running.set(false);

        // Close in reverse order of creation
        if (eventStreamClient != null) {
            try {
                eventStreamClient.close();
            } catch (Exception e) {
                log.warn("Error closing event stream for {}", tenantKey, e);
            }
        }

        if (jobQueueClient != null) {
            try {
                jobQueueClient.close();
            } catch (Exception e) {
                log.warn("Error closing job queue for {}", tenantKey, e);
            }
        }

        if (jobProcessor != null) {
            try {
                jobProcessor.close();
            } catch (Exception e) {
                log.warn("Error closing job processor for {}", tenantKey, e);
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
