package com.tcn.sati.core.route;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tcn.sati.config.SatiConfig;
import com.tcn.sati.core.tenant.TenantContext;
import com.tcn.sati.infra.logging.MemoryLogAppender;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.openapi.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Admin routes for the dashboard: status, logs, and config loading.
 */
public class AdminRoutes {
    private static final Logger log = LoggerFactory.getLogger(AdminRoutes.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private static TenantContext tenantContext;
    private static SatiConfig currentConfig;
    private static Consumer<SatiConfig> configReloadHandler;

    /**
     * Register admin routes.
     * 
     * @param app           Javalin app
     * @param context       Current tenant context (for status)
     * @param config        Current config (for display)
     * @param reloadHandler Optional callback when new config is loaded (null if not
     *                      supported)
     */
    public static void register(Javalin app, TenantContext context, SatiConfig config,
            Consumer<SatiConfig> reloadHandler) {
        tenantContext = context;
        currentConfig = config;
        configReloadHandler = reloadHandler;

        app.get("/api/admin/status", AdminRoutes::getStatus);
        app.get("/api/admin/logs", AdminRoutes::getLogs);
        app.post("/api/admin/config", AdminRoutes::loadConfig);
    }

    // ========== Status ==========

    @OpenApi(path = "/api/admin/status", methods = HttpMethod.GET, summary = "Get system status", tags = {
            "Admin" }, responses = @OpenApiResponse(status = "200", content = @OpenApiContent(from = StatusResponse.class)))
    private static void getStatus(Context ctx) {
        var tenantStatus = tenantContext != null ? tenantContext.getStatus() : null;

        StatusResponse response = new StatusResponse(
                tenantStatus != null && tenantStatus.running(),
                tenantStatus != null && tenantStatus.gateConnected(),
                tenantStatus != null && tenantStatus.backendConnected(),
                tenantStatus != null && tenantStatus.jobQueueConnected(),
                tenantStatus != null && tenantStatus.eventStreamRunning(),
                currentConfig != null ? currentConfig.apiHostname() : null,
                currentConfig != null ? currentConfig.org() : null,
                tenantStatus != null ? tenantStatus.processedJobs() : 0,
                tenantStatus != null ? tenantStatus.failedJobs() : 0,
                tenantStatus != null ? tenantStatus.processedEvents() : 0);

        ctx.json(response);
    }

    public record StatusResponse(
            boolean running,
            boolean gateConnected,
            boolean backendConnected,
            boolean jobQueueConnected,
            boolean eventStreamRunning,
            String apiEndpoint,
            String org,
            long processedJobs,
            long failedJobs,
            long processedEvents) {
    }

    // ========== Logs ==========

    @OpenApi(path = "/api/admin/logs", methods = HttpMethod.GET, summary = "Get recent log messages", tags = {
            "Admin" }, queryParams = @OpenApiParam(name = "limit", type = Integer.class, description = "Max logs to return"), responses = @OpenApiResponse(status = "200"))
    private static void getLogs(Context ctx) {
        int limit = ctx.queryParamAsClass("limit", Integer.class).getOrDefault(100);
        List<String> logs = MemoryLogAppender.getRecentLogs(limit);
        ctx.json(logs);
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

            // Decode base64 config
            byte[] decoded = Base64.getDecoder().decode(req.config.trim());
            var configMap = objectMapper.readValue(decoded, Map.class);

            // Validate required fields
            String[] requiredFields = { "ca_certificate", "certificate", "private_key", "api_endpoint" };
            for (String field : requiredFields) {
                if (!configMap.containsKey(field) || configMap.get(field) == null) {
                    ctx.status(400).json(Map.of("error", "Missing required field: " + field));
                    return;
                }
            }

            // Build new config
            SatiConfig newConfig = SatiConfig.builder()
                    .apiHostname((String) configMap.get("api_endpoint"))
                    .apiPort(443)
                    .rootCert((String) configMap.get("ca_certificate"))
                    .publicCert((String) configMap.get("certificate"))
                    .privateKey((String) configMap.get("private_key"))
                    .org((String) configMap.getOrDefault("org_id", currentConfig != null ? currentConfig.org() : null))
                    .build();

            // Notify handler
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
}
