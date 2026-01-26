package com.tcn.sati;

import com.tcn.sati.config.BackendType;
import com.tcn.sati.config.SatiConfig;
import com.tcn.sati.core.job.JobProcessor;
import com.tcn.sati.core.route.TransferRoutes;
import com.tcn.sati.core.route.AdminRoutes;
import com.tcn.sati.core.route.AgentRoutes;
import com.tcn.sati.core.route.BackendRoutes;
import com.tcn.sati.core.route.GateRoutes;
import io.javalin.http.staticfiles.Location;
import com.tcn.sati.core.service.TransferService;
import com.tcn.sati.core.service.AgentService;
import com.tcn.sati.core.tenant.TenantContext;
import com.tcn.sati.core.tenant.TenantManager;
import com.tcn.sati.infra.gate.GateClient;
import com.tcn.sati.infra.gate.JobStreamClient;
import com.tcn.sati.infra.backend.TenantBackendClient;
import com.tcn.sati.infra.backend.TenantBackendFactory;
import io.javalin.Javalin;
import io.javalin.openapi.plugin.OpenApiPlugin;
import io.javalin.openapi.plugin.swagger.SwaggerPlugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.function.Supplier;

/**
 * Unified entry point for Sati applications.
 * 
 * Supports two modes:
 * - SINGLE_TENANT: For Finvi-style deployments (one customer per instance)
 * - MULTI_TENANT: For Velosidy-style deployments (shared service, multiple customers)
 * 
 * Usage (Single-Tenant - Finvi):
 * <pre>
 * SatiApp.builder()
 *     .config(myConfig)
 *     .backendType(BackendType.JDBC)
 *     .appName("Finvi API")
 *     .start(8080);
 * </pre>
 * 
 * Usage (Multi-Tenant - Velosidy):
 * <pre>
 * SatiApp.builder()
 *     .backendType(BackendType.REST)
 *     .multiTenant(true)
 *     .tenantDiscovery(() -> fetchTenantsFromExternalService())
 *     .discoveryIntervalSeconds(30)
 *     .appName("Velosidy API")
 *     .start(8080);
 * </pre>
 */
public class SatiApp {
    private static final Logger log = LoggerFactory.getLogger(SatiApp.class);

    private final SatiConfig config;
    private final BackendType backendType;
    private final TransferService transferService;
    private final AgentService agentService;
    private final String appName;
    private final boolean enableJobStream;
    private final boolean multiTenant;
    private final Supplier<List<TenantManager.TenantConfig>> tenantDiscovery;
    private final long discoveryIntervalSeconds;
    
    private Javalin app;
    
    // Single-tenant mode resources
    private TenantContext singleTenantContext;
    
    // Multi-tenant mode resources
    private TenantManager tenantManager;

    private SatiApp(Builder builder) {
        this.config = builder.config;
        this.backendType = builder.backendType;
        this.transferService = builder.transferService != null ? builder.transferService : new TransferService();
        this.agentService = builder.agentService != null ? builder.agentService : new AgentService();
        this.appName = builder.appName != null ? builder.appName : backendType.name() + " API";
        this.enableJobStream = builder.enableJobStream;
        this.multiTenant = builder.multiTenant;
        this.tenantDiscovery = builder.tenantDiscovery;
        this.discoveryIntervalSeconds = builder.discoveryIntervalSeconds;
    }

    /**
     * Start the application on the specified port.
     */
    public void start(int port) {
        log.info("Starting {} on port {} (mode: {})", appName, port, multiTenant ? "MULTI_TENANT" : "SINGLE_TENANT");

        if (multiTenant) {
            startMultiTenant();
        } else {
            startSingleTenant();
        }

        // Setup Web Server with Swagger and Static Files
        this.app = Javalin.create(serverConfig -> {
            // Serve static files (admin dashboard)
            serverConfig.staticFiles.add("/public", Location.CLASSPATH);
            
            serverConfig.registerPlugin(new OpenApiPlugin(pluginConfig -> {
                pluginConfig
                    .withDocumentationPath("/swagger-docs")
                    .withDefinitionConfiguration((version, definition) -> {
                        definition.withOpenApiInfo(info -> {
                            info.setTitle(appName);
                            info.setVersion("1.0.0");
                        });
                    });
            }));

            serverConfig.registerPlugin(new SwaggerPlugin(pluginConfig -> {
                pluginConfig.setDocumentationPath("/swagger-docs");
                pluginConfig.setUiPath("/swagger");
            }));
        });
        
        // Redirect root to dashboard
        app.get("/", ctx -> ctx.redirect("/index.html"));

        // Register Routes
        TransferRoutes.register(app, transferService);
        AgentRoutes.register(app, agentService);
        
        // Register tenant-aware routes
        if (multiTenant) {
            registerMultiTenantRoutes();
        } else {
            registerSingleTenantRoutes();
        }

        // Shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutting down {}...", appName);
            
            if (multiTenant && tenantManager != null) {
                tenantManager.close();
            } else if (singleTenantContext != null) {
                singleTenantContext.close();
            }
        }, "sati-shutdown"));

        // Start HTTP server
        app.start(port);
        
        log.info("{} started successfully!", appName);
        log.info("Dashboard: http://localhost:{}/", port);
        log.info("Swagger UI: http://localhost:{}/swagger", port);
        log.info("Mode: {}", multiTenant ? "MULTI_TENANT" : "SINGLE_TENANT");
        
        if (multiTenant) {
            log.info("Tenant discovery: every {}s", discoveryIntervalSeconds);
        }
    }

    private void startSingleTenant() {
        if (config == null) {
            throw new IllegalStateException("Config required for single-tenant mode");
        }
        
        String tenantKey = config.org() != null ? config.org() : "default";
        
        singleTenantContext = new TenantContext(tenantKey, config, backendType);
        singleTenantContext.start();
        
        log.info("Single-tenant mode: tenant '{}' started", tenantKey);
    }

    private void startMultiTenant() {
        tenantManager = new TenantManager(backendType);
        
        if (tenantDiscovery != null) {
            tenantManager.setTenantDiscovery(tenantDiscovery);
            tenantManager.startWithDiscovery(discoveryIntervalSeconds);
            log.info("Multi-tenant mode: automatic discovery enabled");
        } else {
            log.info("Multi-tenant mode: manual tenant creation only");
        }
    }

    private void registerSingleTenantRoutes() {
        BackendRoutes.register(app, singleTenantContext.getBackendClient());
        
        if (singleTenantContext.getGateClient() != null) {
            GateRoutes.register(app, singleTenantContext.getGateClient());
        }
        
        // Status endpoint
        app.get("/api/status", ctx -> {
            ctx.json(singleTenantContext.getStatus());
        });
        
        // Admin routes (dashboard APIs)
        AdminRoutes.register(app, singleTenantContext, config, null);
    }

    private void registerMultiTenantRoutes() {
        // Tenant list endpoint
        app.get("/api/tenants", ctx -> {
            ctx.json(tenantManager.getAllStatus());
        });
        
        // Per-tenant status endpoint
        app.get("/api/tenants/{tenantKey}/status", ctx -> {
            String tenantKey = ctx.pathParam("tenantKey");
            TenantContext tenant = tenantManager.getTenant(tenantKey);
            if (tenant == null) {
                ctx.status(404).json(java.util.Map.of("error", "Tenant not found: " + tenantKey));
            } else {
                ctx.json(tenant.getStatus());
            }
        });
        
        // Per-tenant pool listing
        app.get("/api/tenants/{tenantKey}/pools", ctx -> {
            String tenantKey = ctx.pathParam("tenantKey");
            TenantContext tenant = tenantManager.getTenantOrThrow(tenantKey);
            ctx.json(tenant.getBackendClient().listPools());
        });
        
        // Per-tenant pool records
        app.get("/api/tenants/{tenantKey}/pools/{poolId}/records", ctx -> {
            String tenantKey = ctx.pathParam("tenantKey");
            String poolId = ctx.pathParam("poolId");
            TenantContext tenant = tenantManager.getTenantOrThrow(tenantKey);
            int page = ctx.queryParamAsClass("page", Integer.class).getOrDefault(0);
            ctx.json(tenant.getBackendClient().getPoolRecords(poolId, page));
        });
    }

    // ========== Getters ==========

    public Javalin getApp() {
        return app;
    }

    /**
     * Get the single tenant context (single-tenant mode only).
     */
    public TenantContext getTenantContext() {
        if (multiTenant) {
            throw new IllegalStateException("Use getTenantManager() in multi-tenant mode");
        }
        return singleTenantContext;
    }

    /**
     * Get the tenant manager (multi-tenant mode only).
     */
    public TenantManager getTenantManager() {
        if (!multiTenant) {
            throw new IllegalStateException("Use getTenantContext() in single-tenant mode");
        }
        return tenantManager;
    }

    public boolean isMultiTenant() {
        return multiTenant;
    }

    // ========== Builder ==========

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private SatiConfig config;
        private BackendType backendType;
        private TransferService transferService;
        private AgentService agentService;
        private String appName;
        private boolean enableJobStream = true;
        private boolean multiTenant = false;
        private Supplier<List<TenantManager.TenantConfig>> tenantDiscovery;
        private long discoveryIntervalSeconds = 30;

        public Builder config(SatiConfig config) {
            this.config = config;
            return this;
        }

        public Builder backendType(BackendType backendType) {
            this.backendType = backendType;
            return this;
        }

        public Builder transferService(TransferService service) {
            this.transferService = service;
            return this;
        }

        public Builder agentService(AgentService service) {
            this.agentService = service;
            return this;
        }

        public Builder appName(String name) {
            this.appName = name;
            return this;
        }

        public Builder enableJobStream(boolean enable) {
            this.enableJobStream = enable;
            return this;
        }

        /**
         * Enable multi-tenant mode.
         */
        public Builder multiTenant(boolean multiTenant) {
            this.multiTenant = multiTenant;
            return this;
        }

        /**
         * Set automatic tenant discovery (for multi-tenant mode).
         */
        public Builder tenantDiscovery(Supplier<List<TenantManager.TenantConfig>> discovery) {
            this.tenantDiscovery = discovery;
            return this;
        }

        /**
         * Set tenant discovery interval in seconds (default: 30s).
         */
        public Builder discoveryIntervalSeconds(long seconds) {
            this.discoveryIntervalSeconds = seconds;
            return this;
        }

        public SatiApp build() {
            if (backendType == null) {
                throw new IllegalStateException("BackendType is required");
            }
            if (!multiTenant && config == null) {
                throw new IllegalStateException("Config is required for single-tenant mode");
            }
            return new SatiApp(this);
        }

        public SatiApp start(int port) {
            SatiApp app = build();
            app.start(port);
            return app;
        }
    }
}
