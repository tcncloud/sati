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

import java.nio.file.Files;
import java.nio.file.Paths;
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
    private static String appVersion;

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
            Consumer<SatiConfig> reloadHandler, String version) {
        tenantContext = context;
        currentConfig = config;
        configReloadHandler = reloadHandler;
        appVersion = version;

        app.get("/api/admin/status", AdminRoutes::getStatus);
        app.get("/api/admin/logs", AdminRoutes::getLogs);
        app.post("/api/admin/config", AdminRoutes::loadConfig);
        app.post("/api/admin/restart", AdminRoutes::restart);
    }

    // ========== Status ==========

    @OpenApi(path = "/api/admin/status", methods = HttpMethod.GET, summary = "Get system status", tags = {
            "Admin" }, responses = @OpenApiResponse(status = "200"))
    private static void getStatus(Context ctx) {
        var tenantStatus = tenantContext != null ? tenantContext.getStatus() : null;

        String satiVersion = AdminRoutes.class.getPackage().getImplementationVersion();
        if (satiVersion == null)
            satiVersion = "dev";

        Map<String, Object> response = new java.util.LinkedHashMap<>();

        // Versions
        response.put("satiVersion", satiVersion);
        response.put("appVersion", appVersion != null ? appVersion : "dev");

        // Connection status
        response.put("running", tenantStatus != null && tenantStatus.running());
        response.put("gateConnected", tenantStatus != null && tenantStatus.gateConnected());
        response.put("backendConnected", tenantStatus != null && tenantStatus.backendConnected());
        response.put("jobQueueConnected", tenantStatus != null && tenantStatus.jobQueueConnected());
        response.put("eventStreamRunning", tenantStatus != null && tenantStatus.eventStreamRunning());

        // Config
        response.put("apiEndpoint", currentConfig != null ? currentConfig.apiHostname() : null);
        response.put("org", currentConfig != null ? currentConfig.org() : null);

        // Event stream / job stats
        if (tenantContext != null && tenantContext.getJobProcessor() != null) {
            var jp = tenantContext.getJobProcessor();
            response.put("maxJobs", 5); // default configured in JobProcessor
            response.put("runningJobs", jp.getActiveWorkers());
            response.put("completedJobs", jp.getProcessedJobs());
            response.put("queuedJobs", jp.getQueueSize());
            response.put("failedJobs", jp.getFailedJobs());
        } else {
            response.put("maxJobs", 0);
            response.put("runningJobs", 0);
            response.put("completedJobs", 0);
            response.put("queuedJobs", 0);
            response.put("failedJobs", 0);
        }

        response.put("processedEvents", tenantStatus != null ? tenantStatus.processedEvents() : 0);

        // Backend connection stats (JDBC or REST API)
        if (tenantContext != null
                && tenantContext
                        .getBackendClient() instanceof com.tcn.sati.infra.backend.jdbc.JdbcBackendClient jdbcClient) {
            response.put("jdbc", jdbcClient.getConnectionStats());
            response.put("backendType", "JDBC");
        } else if (tenantContext != null
                && tenantContext
                        .getBackendClient() instanceof com.tcn.sati.infra.backend.rest.RestBackendClient restClient) {
            response.put("api", restClient.getConnectionStats());
            response.put("backendType", "API");
        }

        // TCN connection details (Gate)
        if (tenantContext != null && tenantContext.getGateClient() != null) {
            var gate = tenantContext.getGateClient();
            response.put("orgName", gate.getOrgName());
            response.put("configName", gate.getConfigName());
            response.put("certExpiration", gate.getCertExpiration());
        }

        response.put("configured", currentConfig != null && currentConfig.isConfigured());

        ctx.json(response);
    }

    // ========== Logs ==========

    @OpenApi(path = "/api/admin/logs", methods = HttpMethod.GET, summary = "Get recent log messages", tags = {
            "Admin" }, responses = @OpenApiResponse(status = "200"))
    private static void getLogs(Context ctx) {
        List<String> logs = MemoryLogAppender.getRecentLogs();
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

            // Parse api_endpoint URL into hostname and port
            java.net.URI uri = java.net.URI.create((String) configMap.get("api_endpoint"));
            String hostname = uri.getHost();
            int port = uri.getPort() == -1
                    ? ("https".equals(uri.getScheme()) ? 443 : 80)
                    : uri.getPort();

            // Extract org from certificate DN
            String cert = (String) configMap.get("certificate");
            String orgFromCert = SatiConfig.extractOrgFromCert(cert);

            // Build new config
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
