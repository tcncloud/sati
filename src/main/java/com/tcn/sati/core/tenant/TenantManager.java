package com.tcn.sati.core.tenant;

import com.tcn.sati.config.BackendType;
import com.tcn.sati.config.SatiConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Manages multiple tenant contexts for multi-tenant deployments.
 * 
 * Features:
 * - Thread-safe tenant creation/destruction
 * - Automatic tenant discovery via configurable supplier
 * - Health monitoring
 * - Graceful shutdown
 */
public class TenantManager implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(TenantManager.class);

    private final Map<String, TenantContext> tenants = new ConcurrentHashMap<>();
    private final BackendType defaultBackendType;
    private final ScheduledExecutorService scheduler;

    private Supplier<List<TenantConfig>> tenantDiscovery;
    private volatile boolean running = false;

    /**
     * Configuration for a tenant.
     */
    public record TenantConfig(
            String tenantKey,
            SatiConfig satiConfig,
            BackendType backendType) {
        public TenantConfig(String tenantKey, SatiConfig satiConfig) {
            this(tenantKey, satiConfig, null);
        }
    }

    public TenantManager(BackendType defaultBackendType) {
        this.defaultBackendType = defaultBackendType;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "tenant-manager");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Create a tenant manually.
     */
    public TenantContext createTenant(String tenantKey, SatiConfig config) {
        return createTenant(tenantKey, config, defaultBackendType);
    }

    /**
     * Create a tenant with specific backend type.
     */
    public TenantContext createTenant(String tenantKey, SatiConfig config, BackendType backendType) {
        log.info("Creating tenant: {}", tenantKey);

        // Check if exists
        if (tenants.containsKey(tenantKey)) {
            log.warn("Tenant {} already exists, destroying first", tenantKey);
            destroyTenant(tenantKey);
        }

        TenantContext ctx = new TenantContext(tenantKey, config, backendType);
        ctx.start();
        tenants.put(tenantKey, ctx);

        log.info("Tenant {} created successfully (total: {})", tenantKey, tenants.size());
        return ctx;
    }

    /**
     * Destroy a tenant.
     */
    public void destroyTenant(String tenantKey) {
        TenantContext ctx = tenants.remove(tenantKey);
        if (ctx != null) {
            log.info("Destroying tenant: {}", tenantKey);
            ctx.close();
            log.info("Tenant {} destroyed (remaining: {})", tenantKey, tenants.size());
        }
    }

    /**
     * Get a tenant by key.
     */
    public TenantContext getTenant(String tenantKey) {
        return tenants.get(tenantKey);
    }

    /**
     * Get a tenant, throwing if not found.
     */
    public TenantContext getTenantOrThrow(String tenantKey) {
        TenantContext ctx = tenants.get(tenantKey);
        if (ctx == null) {
            throw new IllegalArgumentException("Tenant not found: " + tenantKey);
        }
        return ctx;
    }

    /**
     * List all tenant keys.
     */
    public List<String> listTenants() {
        return List.copyOf(tenants.keySet());
    }

    /**
     * Get status for all tenants.
     */
    public List<TenantContext.TenantStatus> getAllStatus() {
        return tenants.values().stream()
                .map(TenantContext::getStatus)
                .toList();
    }

    /**
     * Set up automatic tenant discovery.
     * The supplier is called periodically to discover tenants.
     */
    public void setTenantDiscovery(Supplier<List<TenantConfig>> discovery) {
        this.tenantDiscovery = discovery;
    }

    /**
     * Start the tenant manager with automatic discovery.
     */
    public void startWithDiscovery(long intervalSeconds) {
        if (tenantDiscovery == null) {
            throw new IllegalStateException("Tenant discovery not configured");
        }

        log.info("Starting tenant manager with {}s discovery interval", intervalSeconds);
        running = true;

        // Initial discovery
        scheduler.execute(this::discoverTenants);

        // Periodic discovery
        scheduler.scheduleAtFixedRate(this::discoverTenants, intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
    }

    private void discoverTenants() {
        if (!running)
            return;

        try {
            log.debug("Discovering tenants...");
            List<TenantConfig> discovered = tenantDiscovery.get();

            // Create new tenants
            for (TenantConfig tc : discovered) {
                if (!tenants.containsKey(tc.tenantKey())) {
                    try {
                        createTenant(tc.tenantKey(), tc.satiConfig(),
                                tc.backendType() != null ? tc.backendType() : defaultBackendType);
                    } catch (Exception e) {
                        log.error("Failed to create tenant {}: {}", tc.tenantKey(), e.getMessage());
                    }
                }
            }

            // Remove tenants that no longer exist
            List<String> discoveredKeys = discovered.stream().map(TenantConfig::tenantKey).toList();
            for (String existingKey : List.copyOf(tenants.keySet())) {
                if (!discoveredKeys.contains(existingKey)) {
                    log.info("Tenant {} no longer in discovery, removing", existingKey);
                    destroyTenant(existingKey);
                }
            }

            log.debug("Tenant discovery complete: {} tenants active", tenants.size());

        } catch (Exception e) {
            log.error("Error during tenant discovery", e);
        }
    }

    /**
     * Run health checks on all tenants.
     */
    public void healthCheck() {
        for (TenantContext ctx : tenants.values()) {
            boolean healthy = ctx.isHealthy();
            if (!healthy) {
                log.warn("Tenant {} is unhealthy", ctx.getTenantKey());
            }
        }
    }

    public int getTenantCount() {
        return tenants.size();
    }

    @Override
    public void close() {
        log.info("Shutting down tenant manager ({} tenants)", tenants.size());
        running = false;

        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }

        // Close all tenants
        for (String key : List.copyOf(tenants.keySet())) {
            destroyTenant(key);
        }

        log.info("Tenant manager shutdown complete");
    }
}
