package com.tcn.sati.core.route;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tcn.sati.config.SatiConfig;
import com.tcn.sati.core.tenant.TenantContext;
import com.tcn.sati.core.tenant.TenantManager;
import com.tcn.sati.infra.executor.PriorityExecutor;
import com.tcn.sati.infra.logging.MemoryLogAppender;
import com.tcn.sati.infra.metrics.TenantApiMetrics;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.openapi.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Admin routes for the dashboard: status, logs, performance, API metrics, and config loading.
 *
 * <p>Supports both single-tenant and multi-tenant modes. In single-tenant mode, {@code /api/admin/status}
 * and {@code /api/admin/performance} report on the one tenant. In multi-tenant mode those endpoints
 * return aggregate summaries and the dashboard uses the {@code /api/admin/tenants/{key}/*} endpoints
 * to drill into individual tenants.
 */
public class AdminRoutes {
    private static final Logger log = LoggerFactory.getLogger(AdminRoutes.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private static TenantContext tenantContext;
    private static TenantManager tenantManager;
    private static SatiConfig currentConfig;
    private static Consumer<SatiConfig> configReloadHandler;
    private static String appVersion;
    private static TenantApiMetrics apiMetrics;

    private static boolean multiTenant() {
        return tenantManager != null;
    }

    /**
     * Register admin routes. Exactly one of {@code context} or {@code manager} should be non-null.
     *
     * @param app           Javalin app
     * @param context       Tenant context (single-tenant mode); null in multi-tenant
     * @param manager       Tenant manager (multi-tenant mode); null in single-tenant
     * @param config        Current config (for display); may be null in multi-tenant
     * @param reloadHandler Callback when new config is loaded (null if not supported)
     * @param version       App version reported on /status
     * @param metrics       Tenant-scoped HTTP metrics registry
     */
    public static void register(Javalin app, TenantContext context, TenantManager manager,
            SatiConfig config, Consumer<SatiConfig> reloadHandler, String version, TenantApiMetrics metrics) {
        tenantContext = context;
        tenantManager = manager;
        currentConfig = config;
        configReloadHandler = reloadHandler;
        appVersion = version;
        apiMetrics = metrics;

        app.get("/api/admin/status", AdminRoutes::getStatus);
        app.get("/api/admin/logs", AdminRoutes::getLogs);
        app.get("/api/admin/performance", AdminRoutes::getPerformance);
        app.get("/api/admin/api-metrics", AdminRoutes::getApiMetrics);
        app.get("/api/admin/tenants", AdminRoutes::getTenantsList);
        app.get("/api/admin/tenants/{key}/status", AdminRoutes::getTenantStatus);
        app.get("/api/admin/tenants/{key}/performance", AdminRoutes::getTenantPerformance);
        app.get("/api/admin/tenants/{key}/api-metrics", AdminRoutes::getTenantApiMetrics);
        app.post("/api/admin/config", AdminRoutes::loadConfig);
        app.post("/api/admin/restart", AdminRoutes::restart);
    }

    // ========== Status ==========

    @OpenApi(path = "/api/admin/status", methods = HttpMethod.GET, summary = "Get system status", tags = {
            "Admin" }, responses = @OpenApiResponse(status = "200"))
    private static void getStatus(Context ctx) {
        Map<String, Object> response = baseStatus();
        response.put("multiTenant", multiTenant());

        if (multiTenant()) {
            response.putAll(aggregateStatus());
        } else {
            response.putAll(tenantStatus(tenantContext));
        }

        ctx.json(response);
    }

    // ========== Logs ==========

    @OpenApi(path = "/api/admin/logs", methods = HttpMethod.GET, summary = "Get recent log messages", tags = {
            "Admin" }, responses = @OpenApiResponse(status = "200"))
    private static void getLogs(Context ctx) {
        List<String> logs = MemoryLogAppender.getRecentLogs();
        ctx.json(logs);
    }

    // ========== Performance ==========

    @OpenApi(path = "/api/admin/performance", methods = HttpMethod.GET, summary = "Get performance stats", tags = {
            "Admin" }, responses = @OpenApiResponse(status = "200"))
    private static void getPerformance(Context ctx) {
        if (multiTenant()) {
            ctx.json(aggregatePerformance());
        } else {
            ctx.json(tenantPerformance(tenantContext));
        }
    }

    // ========== API Metrics ==========

    @OpenApi(path = "/api/admin/api-metrics", methods = HttpMethod.GET, summary = "Per-endpoint HTTP metrics (aggregate)", tags = {
            "Admin" }, responses = @OpenApiResponse(status = "200"))
    private static void getApiMetrics(Context ctx) {
        ctx.json(Map.of("endpoints", apiMetrics != null ? apiMetrics.snapshotAll() : List.of()));
    }

    // ========== Per-tenant endpoints ==========

    @OpenApi(path = "/api/admin/tenants", methods = HttpMethod.GET, summary = "List tenant keys", tags = {
            "Admin" }, responses = @OpenApiResponse(status = "200"))
    private static void getTenantsList(Context ctx) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("multiTenant", multiTenant());
        List<String> keys = new ArrayList<>();
        if (multiTenant()) {
            keys.addAll(tenantManager.listTenants());
        } else if (tenantContext != null) {
            keys.add(tenantContext.getTenantKey());
        }
        response.put("tenants", keys);
        // Include metrics-only keys (e.g. "_global") for completeness.
        if (apiMetrics != null) {
            List<String> metricKeys = apiMetrics.tenants().stream()
                    .filter(k -> !keys.contains(k)).toList();
            response.put("metricKeys", metricKeys);
        }
        ctx.json(response);
    }

    @OpenApi(path = "/api/admin/tenants/{key}/status", methods = HttpMethod.GET, summary = "Per-tenant status", tags = {
            "Admin" }, pathParams = @OpenApiParam(name = "key", required = true), responses = @OpenApiResponse(status = "200"))
    private static void getTenantStatus(Context ctx) {
        TenantContext target = resolveTenant(ctx);
        if (target == null) {
            ctx.status(404).json(Map.of("error", "Tenant not found: " + ctx.pathParam("key")));
            return;
        }
        Map<String, Object> response = baseStatus();
        response.put("multiTenant", multiTenant());
        response.put("tenantKey", target.getTenantKey());
        response.putAll(tenantStatus(target));
        ctx.json(response);
    }

    @OpenApi(path = "/api/admin/tenants/{key}/performance", methods = HttpMethod.GET, summary = "Per-tenant performance", tags = {
            "Admin" }, pathParams = @OpenApiParam(name = "key", required = true), responses = @OpenApiResponse(status = "200"))
    private static void getTenantPerformance(Context ctx) {
        TenantContext target = resolveTenant(ctx);
        if (target == null) {
            ctx.status(404).json(Map.of("error", "Tenant not found: " + ctx.pathParam("key")));
            return;
        }
        ctx.json(tenantPerformance(target));
    }

    @OpenApi(path = "/api/admin/tenants/{key}/api-metrics", methods = HttpMethod.GET, summary = "Per-tenant HTTP metrics", tags = {
            "Admin" }, pathParams = @OpenApiParam(name = "key", required = true), responses = @OpenApiResponse(status = "200"))
    private static void getTenantApiMetrics(Context ctx) {
        String key = ctx.pathParam("key");
        List<Map<String, Object>> endpoints = apiMetrics != null ? apiMetrics.snapshot(key) : List.of();
        ctx.json(Map.of("tenantKey", key, "endpoints", endpoints));
    }

    // ========== Helpers ==========

    private static Map<String, Object> baseStatus() {
        Map<String, Object> response = new LinkedHashMap<>();
        String satiVersion = AdminRoutes.class.getPackage().getImplementationVersion();
        if (satiVersion == null) satiVersion = "dev";
        response.put("satiVersion", satiVersion);
        response.put("appVersion", appVersion != null ? appVersion : "dev");
        return response;
    }

    /** Status fields for a single tenant context — same shape as the legacy single-tenant status. */
    private static Map<String, Object> tenantStatus(TenantContext tc) {
        Map<String, Object> response = new LinkedHashMap<>();
        var ts = tc != null ? tc.getStatus() : null;

        response.put("running", ts != null && ts.running());
        response.put("gateConnected", ts != null && ts.gateConnected());
        response.put("backendConnected", ts != null && ts.backendConnected());
        response.put("workStreamConnected", ts != null && ts.workStreamConnected());

        response.put("apiEndpoint", currentConfig != null ? currentConfig.apiHostname() : null);
        response.put("org", currentConfig != null ? currentConfig.org() : null);

        response.put("completedWork", ts != null ? ts.completedWork() : 0);
        response.put("failedWork", ts != null ? ts.failedWork() : 0);
        if (tc != null && tc.getWorkStreamClient() != null) {
            response.put("streamPhase", tc.getWorkStreamClient().getPhase().name());
            response.put("inflightWork", tc.getWorkStreamClient().getInflight());
        }

        if (tc != null && tc.getBackendClient() instanceof com.tcn.sati.infra.backend.jdbc.JdbcBackendClient jdbcClient) {
            response.put("jdbc", jdbcClient.getConnectionStats());
            response.put("backendType", "JDBC");
        } else if (tc != null
                && tc.getBackendClient() instanceof com.tcn.sati.infra.backend.rest.RestBackendClient restClient) {
            response.put("api", restClient.getConnectionStats());
            response.put("backendType", "API");
        }

        if (tc != null && tc.getGateClient() != null) {
            var gate = tc.getGateClient();
            response.put("orgName", gate.getOrgName());
            response.put("configName", gate.getConfigName());
            response.put("certExpiration", gate.getCertExpiration());
        }

        response.put("configured", currentConfig != null && currentConfig.isConfigured());

        if (tc != null) {
            tc.adaptiveSnapshot().ifPresent(s -> {
                response.put("adaptiveLimit", s.limit());
                response.put("adaptiveCeiling", s.effectiveCeiling());
                response.put("adaptiveP95Ms", s.jobP95Millis());
                response.put("adaptiveEmaMs", s.jobEmaMillis());
                response.put("adaptiveDecayingMinMs", s.decayingMinMillis());
                response.put("adaptiveUtilization",
                        s.effectiveCeiling() > 0 ? (100.0 * s.limit() / s.effectiveCeiling()) : 0.0);
                response.put("adaptiveSloGradient", s.sloGradient());
                response.put("adaptiveMinGradient", s.minGradient());
                response.put("adaptiveResourceGradient", s.resourceGradient());
                response.put("adaptiveSamples", s.sampleCount());
                response.put("adaptiveErrors", s.errorCount());
            });
        }

        return response;
    }

    /** Performance fields for a single tenant context. */
    private static Map<String, Object> tenantPerformance(TenantContext tc) {
        Map<String, Object> response = new LinkedHashMap<>();
        if (tc != null && tc.getWorkStreamClient() != null) {
            var ws = tc.getWorkStreamClient();
            response.put("streamPhase", ws.getPhase().name());
            response.put("inflightWork", ws.getInflight());
            response.put("completedTotal", ws.getCompletedTotal());
            response.put("failedTotal", ws.getFailedTotal());
            response.put("reconnectAttempts", ws.getReconnectAttempts());
        }
        PriorityExecutor pe = tc != null ? tc.getPriorityExecutor() : null;
        if (pe != null) {
            response.put("executor", pe.getStats());
        }
        return response;
    }

    /** Multi-tenant status summary — counts + per-tenant health list. */
    private static Map<String, Object> aggregateStatus() {
        Map<String, Object> response = new LinkedHashMap<>();
        var all = tenantManager.getAllStatus();
        int total = all.size();
        long running = all.stream().filter(TenantContext.TenantStatus::running).count();
        long gateOk = all.stream().filter(TenantContext.TenantStatus::gateConnected).count();
        long backendOk = all.stream().filter(TenantContext.TenantStatus::backendConnected).count();
        long wsOk = all.stream().filter(TenantContext.TenantStatus::workStreamConnected).count();
        long completed = all.stream().mapToLong(TenantContext.TenantStatus::completedWork).sum();
        long failed = all.stream().mapToLong(TenantContext.TenantStatus::failedWork).sum();

        response.put("tenantCount", total);
        response.put("tenantsRunning", running);
        response.put("tenantsGateConnected", gateOk);
        response.put("tenantsBackendConnected", backendOk);
        response.put("tenantsWorkStreamConnected", wsOk);
        response.put("completedWorkTotal", completed);
        response.put("failedWorkTotal", failed);

        // Compact per-tenant list for the selector UI.
        List<Map<String, Object>> tenantList = new ArrayList<>(total);
        for (var ts : all) {
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("tenantKey", ts.tenantKey());
            t.put("running", ts.running());
            t.put("gateConnected", ts.gateConnected());
            t.put("backendConnected", ts.backendConnected());
            t.put("workStreamConnected", ts.workStreamConnected());
            tenantList.add(t);
        }
        response.put("tenants", tenantList);
        return response;
    }

    /** Multi-tenant performance summary — aggregate WorkStream counts only; no executor. */
    private static Map<String, Object> aggregatePerformance() {
        Map<String, Object> response = new LinkedHashMap<>();
        if (tenantManager == null) return response;
        long completed = 0, failed = 0, inflight = 0, reconnects = 0;
        for (String key : tenantManager.listTenants()) {
            TenantContext tc = tenantManager.getTenant(key);
            if (tc == null || tc.getWorkStreamClient() == null) continue;
            var ws = tc.getWorkStreamClient();
            completed += ws.getCompletedTotal();
            failed += ws.getFailedTotal();
            inflight += ws.getInflight();
            reconnects += ws.getReconnectAttempts();
        }
        response.put("completedTotal", completed);
        response.put("failedTotal", failed);
        response.put("inflightWork", inflight);
        response.put("reconnectAttempts", reconnects);
        response.put("tenantCount", tenantManager.getTenantCount());
        return response;
    }

    private static TenantContext resolveTenant(Context ctx) {
        String key = ctx.pathParam("key");
        if (multiTenant()) {
            return tenantManager.getTenant(key);
        }
        // Single-tenant: honor the request only if the key matches the configured tenant.
        if (tenantContext != null && key.equals(tenantContext.getTenantKey())) {
            return tenantContext;
        }
        return null;
    }

    // ========== Config Loading ==========

    @OpenApi(path = "/api/admin/config", methods = HttpMethod.POST, summary = "Load new configuration", tags = {
            "Admin" }, requestBody = @OpenApiRequestBody(content = @OpenApiContent(from = ConfigRequest.class)), responses = {
                    @OpenApiResponse(status = "200", content = @OpenApiContent(from = ConfigResponse.class)),
                    @OpenApiResponse(status = "400", description = "Invalid config"),
                    @OpenApiResponse(status = "501", description = "Config reload not supported")
            })
    private static void loadConfig(Context ctx) {
        if (configReloadHandler == null) {
            ctx.status(501).json(Map.of("error", "Config reload not supported in this mode"));
            return;
        }

        try {
            ConfigRequest req = ctx.bodyAsClass(ConfigRequest.class);

            if (req.config == null || req.config.isBlank()) {
                ctx.status(400).json(Map.of("error", "Config content required"));
                return;
            }

            byte[] decoded = Base64.getDecoder().decode(req.config.trim());
            var configMap = objectMapper.readValue(decoded, Map.class);

            String[] requiredFields = { "ca_certificate", "certificate", "private_key", "api_endpoint" };
            for (String field : requiredFields) {
                if (!configMap.containsKey(field) || configMap.get(field) == null) {
                    ctx.status(400).json(Map.of("error", "Missing required field: " + field));
                    return;
                }
            }

            java.net.URI uri = java.net.URI.create((String) configMap.get("api_endpoint"));
            String hostname = uri.getHost();
            int port = uri.getPort() == -1
                    ? ("https".equals(uri.getScheme()) ? 443 : 80)
                    : uri.getPort();

            String cert = (String) configMap.get("certificate");
            String orgFromCert = SatiConfig.extractOrgFromCert(cert);

            SatiConfig newConfig = SatiConfig.builder()
                    .apiHostname(hostname)
                    .apiPort(port)
                    .rootCert((String) configMap.get("ca_certificate"))
                    .publicCert(cert)
                    .privateKey((String) configMap.get("private_key"))
                    .org(orgFromCert != null ? orgFromCert
                            : (String) configMap.getOrDefault("org_id",
                                    currentConfig != null ? currentConfig.org() : null))
                    .tenant(orgFromCert != null ? orgFromCert
                            : currentConfig != null ? currentConfig.tenant() : null)
                    .build();

            configReloadHandler.accept(newConfig);
            currentConfig = newConfig;

            log.info("Configuration loaded successfully for endpoint: {}", newConfig.apiHostname());
            ctx.json(new ConfigResponse(true, "Configuration loaded successfully"));

        } catch (IllegalArgumentException e) {
            ctx.status(400).json(Map.of("error", "Invalid base64 encoding"));
        } catch (Exception e) {
            log.error("Failed to load config", e);
            ctx.status(400).json(Map.of("error", "Failed to parse config: " + e.getMessage()));
        }
    }

    public record ConfigRequest(String config) {
    }

    public record ConfigResponse(boolean success, String message) {
    }

    // ========== Restart ==========

    @OpenApi(path = "/api/admin/restart", methods = HttpMethod.POST, summary = "Restart the application", tags = {
            "Admin" }, responses = @OpenApiResponse(status = "202"))
    private static void restart(Context ctx) {
        log.info("Restart requested");
        try {
            Files.createDirectories(Paths.get("/tmp/restart-trigger"));
            Files.write(Paths.get("/tmp/restart-trigger/restart"), "restart".getBytes());
            log.info("Created restart trigger file - wrapper script will handle restart");
        } catch (Exception e) {
            log.info("Wrapper script not available. Ignoring restart request.");
        }
        ctx.status(202).result("Restart triggered");
    }
}
