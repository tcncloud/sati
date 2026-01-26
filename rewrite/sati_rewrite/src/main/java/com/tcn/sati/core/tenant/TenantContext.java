package com.tcn.sati.core.tenant;

import com.tcn.sati.config.BackendType;
import com.tcn.sati.config.SatiConfig;
import com.tcn.sati.core.job.JobProcessor;
import com.tcn.sati.infra.backend.TenantBackendClient;
import com.tcn.sati.infra.backend.TenantBackendFactory;
import com.tcn.sati.infra.gate.GateClient;
import com.tcn.sati.infra.gate.JobStreamClient;
import com.tcn.sati.infra.backend.jdbc.JdbcBackendClient;
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
 * - JobStreamClient (receives jobs from Gate)
 * - JobProcessor (executes jobs)
 * - TenantBackendClient (database/API connection)
 * - ScheduledExecutorService (for tenant-specific tasks)
 */
public class TenantContext implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(TenantContext.class);

    private final String tenantKey;
    private final SatiConfig config;
    private final BackendType backendType;
    
    private GateClient gateClient;
    private TenantBackendClient backendClient;
    private JobProcessor jobProcessor;
    private JobStreamClient jobStreamClient;
    private ScheduledExecutorService scheduler;
    
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Instant createdAt = Instant.now();
    private volatile Instant lastHealthCheck;

    public TenantContext(String tenantKey, SatiConfig config, BackendType backendType) {
        this.tenantKey = tenantKey;
        this.config = config;
        this.backendType = backendType;
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
            this.backendClient = TenantBackendFactory.create(config, backendType);

            // 4. Wire Gate config polling to Backend Client (for dynamic DB config)
            if (gateClient != null && backendType == BackendType.JDBC && backendClient instanceof JdbcBackendClient) {
                var jdbcClient = (JdbcBackendClient) backendClient;
                gateClient.setConfigListener(jdbcClient::onBackendConfigReceived);
                gateClient.startConfigPolling();
                log.info("Tenant {}: Gate config polling started", tenantKey);
            }

            // 5. Initialize Job Processing
            if (gateClient != null) {
                this.jobProcessor = new JobProcessor(backendClient, gateClient);
                this.jobStreamClient = new JobStreamClient(gateClient, jobProcessor::processJob);
                this.jobStreamClient.start();
                log.info("Tenant {}: Job stream started", tenantKey);
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
        
        if (!running.get()) return false;
        if (gateClient != null && !gateClient.isChannelActive()) return false;
        if (backendClient != null && !backendClient.isConnected()) return false;
        
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

    public JobStreamClient getJobStreamClient() {
        return jobStreamClient;
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
            jobStreamClient != null ? jobStreamClient.isConnected() : false,
            jobProcessor != null ? jobProcessor.getProcessedJobs() : 0,
            jobProcessor != null ? jobProcessor.getFailedJobs() : 0,
            createdAt
        );
    }

    public record TenantStatus(
        String tenantKey,
        boolean running,
        boolean gateConnected,
        boolean backendConnected,
        boolean jobStreamConnected,
        long processedJobs,
        long failedJobs,
        Instant createdAt
    ) {}

    @Override
    public void close() {
        log.info("Shutting down tenant: {}", tenantKey);
        running.set(false);
        
        // Close in reverse order of creation
        if (jobStreamClient != null) {
            try { jobStreamClient.close(); } catch (Exception e) { log.warn("Error closing job stream for {}", tenantKey, e); }
        }
        
        if (jobProcessor != null) {
            try { jobProcessor.close(); } catch (Exception e) { log.warn("Error closing job processor for {}", tenantKey, e); }
        }
        
        if (gateClient != null) {
            try { gateClient.close(); } catch (Exception e) { log.warn("Error closing gate client for {}", tenantKey, e); }
        }
        
        if (backendClient != null) {
            try { backendClient.close(); } catch (Exception e) { log.warn("Error closing backend client for {}", tenantKey, e); }
        }
        
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
        
        log.info("Tenant {} shutdown complete", tenantKey);
    }
}
