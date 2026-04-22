package com.tcn.sati;

import com.tcn.sati.config.BackendType;
import com.tcn.sati.config.SatiConfig;
import com.tcn.sati.core.route.*;
import com.tcn.sati.core.service.*;
import io.javalin.http.staticfiles.Location;
import com.tcn.sati.core.tenant.TenantContext;
import com.tcn.sati.core.tenant.TenantManager;
import com.tcn.sati.infra.backend.TenantBackendClient;
import com.tcn.sati.infra.metrics.ApiMetricsRecorder;
import com.tcn.sati.infra.metrics.TenantApiMetrics;
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
 * - SINGLE_TENANT: For database-style deployments (one customer per instance)
 * - MULTI_TENANT: For api-style deployments (shared service, multiple
 * customers)
 * 
 * Usage (Single-Tenant - database):
 * 
 * <pre>
 * SatiApp.builder()
 *         .config(myConfig)
 *         .backendType(BackendType.JDBC)
 *         .appName("APP")
 *         .start(8080);
 * </pre>
 * 
 * Usage (Multi-Tenant - api):
 * 
 * <pre>
 * SatiApp.builder()
 *         .backendType(BackendType.REST)
 *         .multiTenant(true)
 *         .tenantDiscovery(() -> fetchTenantsFromExternalService())
 *         .discoveryIntervalSeconds(30)
 *         .appName("API")
 *         .start(8080);
 * </pre>
 */
public class SatiApp {
    private static final Logger log = LoggerFactory.getLogger(SatiApp.class);

    private final SatiConfig config;
    private final BackendType backendType;
    private final TenantBackendClient customBackendClient;
    private final String appName;
    private final String appVersion;
    private final boolean multiTenant;
    private final int priorityPoolSize;
    private final int priorityMaxLowDepth;
    private final Supplier<List<TenantManager.TenantConfig>> tenantDiscovery;
    private final long discoveryIntervalSeconds;
    private final boolean adaptiveEnabled;
    private final int minConcurrency;
    private final int initialConcurrency;
    private final int maxConcurrency;
    private final java.time.Duration shutdownDrainTimeout;
    private final double tracingSamplingFraction;

    // Optional service override factories (can provide custom constructors)
    private final java.util.function.Function<com.tcn.sati.infra.gate.GateClient, TransferService> transferServiceFactory;
    private final java.util.function.Function<com.tcn.sati.infra.gate.GateClient, AgentService> agentServiceFactory;
    private final java.util.function.Function<com.tcn.sati.infra.gate.GateClient, ScrubListService> scrubListServiceFactory;
    private final java.util.function.Function<com.tcn.sati.infra.gate.GateClient, SkillsService> skillsServiceFactory;
    private final java.util.function.Function<com.tcn.sati.infra.gate.GateClient, NCLRulesetService> nclRulesetServiceFactory;
    private final java.util.function.Function<com.tcn.sati.infra.gate.GateClient, VoiceRecordingService> voiceRecordingServiceFactory;
    private final java.util.function.Function<com.tcn.sati.infra.gate.GateClient, JourneyBufferService> journeyBufferServiceFactory;

    private Javalin app;
    private final TenantApiMetrics apiMetrics = new TenantApiMetrics();

    // Single-tenant mode resources
    private TenantContext singleTenantContext;

    // Multi-tenant mode resources
    private TenantManager tenantManager;

    private SatiApp(Builder builder) {
        this.config = builder.config;
        this.backendType = builder.backendType;
        this.customBackendClient = builder.customBackendClient;
        this.appName = builder.appName != null ? builder.appName : backendType.name() + " API";
        this.appVersion = builder.appVersion;
        this.multiTenant = builder.multiTenant;
        this.tenantDiscovery = builder.tenantDiscovery;
        this.discoveryIntervalSeconds = builder.discoveryIntervalSeconds;
        this.transferServiceFactory = builder.transferServiceFactory;
        this.agentServiceFactory = builder.agentServiceFactory;
        this.scrubListServiceFactory = builder.scrubListServiceFactory;
        this.skillsServiceFactory = builder.skillsServiceFactory;
        this.nclRulesetServiceFactory = builder.nclRulesetServiceFactory;
        this.voiceRecordingServiceFactory = builder.voiceRecordingServiceFactory;
        this.journeyBufferServiceFactory = builder.journeyBufferServiceFactory;
        this.priorityPoolSize = builder.priorityPoolSize;
        this.priorityMaxLowDepth = builder.priorityMaxLowDepth;
        this.adaptiveEnabled = builder.adaptiveEnabled;
        this.minConcurrency = builder.minConcurrency;
        this.initialConcurrency = builder.initialConcurrency;
        this.maxConcurrency = builder.maxConcurrency;
        this.shutdownDrainTimeout = builder.shutdownDrainTimeout;
        this.tracingSamplingFraction = builder.tracingSamplingFraction;
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

        // Register API metrics recorder BEFORE any routes so all endpoints are instrumented.
        ApiMetricsRecorder.register(app, apiMetrics);

        // Redirect root to dashboard
        app.get("/", ctx -> ctx.redirect("/index.html"));

        // Register tenant-aware routes
        if (multiTenant) {
            registerMultiTenantRoutes();
        } else {
            registerSingleTenantRoutes();
        }

        // Filter swagger docs to only show routes for the current mode
        final boolean isMultiTenant = multiTenant;
        app.after("/swagger-docs*", ctx -> {
            if (!"application/json".equals(ctx.res().getContentType())
                    && !"application/json;charset=utf-8".equalsIgnoreCase(ctx.res().getContentType())) {
                return;
            }
            String body = ctx.result();
            if (body == null || body.isEmpty())
                return;
            try {
                var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                var root = mapper.readTree(body);
                var paths = (com.fasterxml.jackson.databind.node.ObjectNode) root.get("paths");
                if (paths == null)
                    return;
                var toRemove = new java.util.ArrayList<String>();
                paths.fieldNames().forEachRemaining(path -> {
                    if (isMultiTenant) {
                        // In multi-tenant mode, remove flat /api/* routes (keep /api/orgs/* and
                        // /api/admin/*)
                        if (!path.startsWith("/api/orgs") && !path.startsWith("/api/admin")) {
                            toRemove.add(path);
                        }
                    } else {
                        // In single-tenant mode, remove /api/orgs/* routes
                        if (path.startsWith("/api/orgs")) {
                            toRemove.add(path);
                        }
                    }
                });
                toRemove.forEach(paths::remove);
                ctx.result(mapper.writeValueAsString(root));
            } catch (Exception e) {
                // If filtering fails, just serve the original spec
                log.debug("Failed to filter OpenAPI spec", e);
            }
        });

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

        String tenantKey = config.tenant() != null ? config.tenant() : "default";

        // Use custom backend if provided, otherwise TenantContext creates one via
        // factory
        if (customBackendClient != null) {
            singleTenantContext = new TenantContext(tenantKey, config, null,
                    customBackendClient, appName, appVersion);
        } else {
            singleTenantContext = new TenantContext(tenantKey, config, backendType,
                    null, appName, appVersion);
        }
        if (priorityPoolSize > 0) {
            singleTenantContext.setPriorityExecutorConfig(priorityPoolSize, priorityMaxLowDepth);
        }
        singleTenantContext.setAdaptiveConfig(adaptiveEnabled, minConcurrency, initialConcurrency, maxConcurrency);
        singleTenantContext.setShutdownDrainTimeout(shutdownDrainTimeout);
        singleTenantContext.setTracingSamplingFraction(tracingSamplingFraction);
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

        var gate = singleTenantContext.getGateClient();
        if (gate != null) {
            GateRoutes.register(app, gate);

            // Create services — use factory if provided, otherwise default with GateClient
            AgentRoutes.register(app,
                    agentServiceFactory != null ? agentServiceFactory.apply(gate) : new AgentService(gate));
            TransferRoutes.register(app,
                    transferServiceFactory != null ? transferServiceFactory.apply(gate) : new TransferService(gate));
            ScrubListRoutes.register(app,
                    scrubListServiceFactory != null ? scrubListServiceFactory.apply(gate) : new ScrubListService(gate));
            SkillsRoutes.register(app,
                    skillsServiceFactory != null ? skillsServiceFactory.apply(gate) : new SkillsService(gate));
            NCLRulesetRoutes.register(app,
                    nclRulesetServiceFactory != null ? nclRulesetServiceFactory.apply(gate)
                            : new NCLRulesetService(gate));
            VoiceRecordingRoutes.register(app,
                    voiceRecordingServiceFactory != null ? voiceRecordingServiceFactory.apply(gate)
                            : new VoiceRecordingService(gate));
            JourneyBufferRoutes.register(app,
                    journeyBufferServiceFactory != null ? journeyBufferServiceFactory.apply(gate)
                            : new JourneyBufferService(gate));
        }

        // Status endpoint
        app.get("/api/status", ctx -> {
            ctx.json(singleTenantContext.getStatus());
        });

        // Admin routes (dashboard APIs)
        AdminRoutes.register(app, singleTenantContext, null, config,
                singleTenantContext::reconnectGate, appVersion, apiMetrics);
    }

    private void registerMultiTenantRoutes() {
        OrgRoutes.register(app, tenantManager);
        // Admin routes: tenant-manager-aware. Status/performance return aggregate views;
        // per-tenant drill-down via /api/admin/tenants/{key}/*.
        AdminRoutes.register(app, null, tenantManager, null, null, appVersion, apiMetrics);
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
        private TenantBackendClient customBackendClient;
        private String appName;
        private String appVersion;
        private boolean multiTenant = false;
        private Supplier<List<TenantManager.TenantConfig>> tenantDiscovery;
        private long discoveryIntervalSeconds = 30;
        private int priorityPoolSize = 0;     // 0 = disabled
        private int priorityMaxLowDepth = 100;
        private boolean adaptiveEnabled = true;
        private int minConcurrency = 1;
        private int initialConcurrency = 10;
        private int maxConcurrency = 100;
        private java.time.Duration shutdownDrainTimeout = java.time.Duration.ofSeconds(30);
        private double tracingSamplingFraction = 0.0;

        // Service override factories
        private java.util.function.Function<com.tcn.sati.infra.gate.GateClient, TransferService> transferServiceFactory;
        private java.util.function.Function<com.tcn.sati.infra.gate.GateClient, AgentService> agentServiceFactory;
        private java.util.function.Function<com.tcn.sati.infra.gate.GateClient, ScrubListService> scrubListServiceFactory;
        private java.util.function.Function<com.tcn.sati.infra.gate.GateClient, SkillsService> skillsServiceFactory;
        private java.util.function.Function<com.tcn.sati.infra.gate.GateClient, NCLRulesetService> nclRulesetServiceFactory;
        private java.util.function.Function<com.tcn.sati.infra.gate.GateClient, VoiceRecordingService> voiceRecordingServiceFactory;
        private java.util.function.Function<com.tcn.sati.infra.gate.GateClient, JourneyBufferService> journeyBufferServiceFactory;

        public Builder config(SatiConfig config) {
            this.config = config;
            return this;
        }

        public Builder backendType(BackendType backendType) {
            this.backendType = backendType;
            return this;
        }

        /**
         * Set a custom backend client (skips factory creation).
         * Use this to inject tenant-specific implementations (e.g.,
         * CustomAppBackendClient).
         */
        public Builder backendClient(TenantBackendClient client) {
            this.customBackendClient = client;
            return this;
        }

        public Builder appName(String name) {
            this.appName = name;
            return this;
        }

        public Builder appVersion(String version) {
            this.appVersion = version;
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

        /**
         * Enable priority execution with the given thread pool size and max concurrent LOW items.
         * HIGH-priority work (interactive jobs) is served before LOW-priority work (events).
         */
        public Builder priorityExecutor(int poolSize, int maxLowDepth) {
            this.priorityPoolSize = poolSize;
            this.priorityMaxLowDepth = maxLowDepth;
            return this;
        }

        /** Enable or disable the adaptive concurrency controller (default: enabled). */
        public Builder adaptive(boolean enabled) {
            this.adaptiveEnabled = enabled;
            return this;
        }

        /** Minimum concurrency limit for the adaptive controller (default: 1). */
        public Builder minConcurrency(int n) {
            this.minConcurrency = n;
            return this;
        }

        /** Initial concurrency limit before the adaptive controller has enough samples (default: 10). */
        public Builder initialConcurrency(int n) {
            this.initialConcurrency = n;
            return this;
        }

        /** Maximum concurrency limit for the adaptive controller (default: 100). */
        public Builder maxConcurrency(int n) {
            this.maxConcurrency = n;
            return this;
        }

        /** Graceful drain timeout on shutdown (default: 30s). */
        public Builder shutdownDrainTimeout(java.time.Duration d) {
            this.shutdownDrainTimeout = d;
            return this;
        }

        /**
         * GCP Cloud Trace sampling fraction (0.0 = disabled, 1.0 = 100%; default: 0.0).
         * When > 0, spans are exported to GCP Cloud Trace. Requires Application Default Credentials.
         */
        public Builder tracingSamplingFraction(double fraction) {
            this.tracingSamplingFraction = fraction;
            return this;
        }

        // --- Service override methods (accept factory: GateClient -> Service) ---

        public Builder transferService(
                java.util.function.Function<com.tcn.sati.infra.gate.GateClient, TransferService> factory) {
            this.transferServiceFactory = factory;
            return this;
        }

        public Builder agentService(
                java.util.function.Function<com.tcn.sati.infra.gate.GateClient, AgentService> factory) {
            this.agentServiceFactory = factory;
            return this;
        }

        public Builder scrubListService(
                java.util.function.Function<com.tcn.sati.infra.gate.GateClient, ScrubListService> factory) {
            this.scrubListServiceFactory = factory;
            return this;
        }

        public Builder skillsService(
                java.util.function.Function<com.tcn.sati.infra.gate.GateClient, SkillsService> factory) {
            this.skillsServiceFactory = factory;
            return this;
        }

        public Builder nclRulesetService(
                java.util.function.Function<com.tcn.sati.infra.gate.GateClient, NCLRulesetService> factory) {
            this.nclRulesetServiceFactory = factory;
            return this;
        }

        public Builder voiceRecordingService(
                java.util.function.Function<com.tcn.sati.infra.gate.GateClient, VoiceRecordingService> factory) {
            this.voiceRecordingServiceFactory = factory;
            return this;
        }

        public Builder journeyBufferService(
                java.util.function.Function<com.tcn.sati.infra.gate.GateClient, JourneyBufferService> factory) {
            this.journeyBufferServiceFactory = factory;
            return this;
        }

        public SatiApp build() {
            if (backendType == null && customBackendClient == null) {
                throw new IllegalStateException("BackendType or custom BackendClient is required");
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
