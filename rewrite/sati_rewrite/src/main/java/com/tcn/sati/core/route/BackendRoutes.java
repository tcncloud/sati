package com.tcn.sati.core.route;

import com.tcn.sati.infra.backend.TenantBackendClient;
import com.tcn.sati.infra.backend.TenantBackendClient.*;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.openapi.*;

import java.util.List;

/**
 * Routes for tenant backend operations (pools, records, etc.)
 * Works with both Finvi (database) and Velosidy (API) backends.
 */
public class BackendRoutes {

    private static TenantBackendClient backendClient;

    public static void register(Javalin app, TenantBackendClient client) {
        backendClient = client;
        
        // Health check
        app.get("/api/backend/health", BackendRoutes::checkHealth);
        
        // Pool operations
        app.get("/api/pools", BackendRoutes::listPools);
        app.get("/api/pools/{poolId}/status", BackendRoutes::getPoolStatus);
        app.get("/api/pools/{poolId}/records", BackendRoutes::getPoolRecords);
    }

    @OpenApi(path = "/api/backend/health", methods = HttpMethod.GET, summary = "Check Backend Health", tags = {"Backend"}, responses = {
            @OpenApiResponse(status = "200", content = @OpenApiContent(from = HealthResponse.class))
    })
    private static void checkHealth(Context ctx) {
        if (backendClient == null) {
            ctx.status(503).json(new HealthResponse(false, "Backend not configured"));
            return;
        }

        boolean connected = backendClient.isConnected();
        ctx.json(new HealthResponse(connected, connected ? "Connected" : "Failed to connect"));
    }

    @OpenApi(path = "/api/pools", methods = HttpMethod.GET, summary = "List Pools", tags = {"Pools"}, responses = {
            @OpenApiResponse(status = "200", content = @OpenApiContent(from = PoolInfo[].class))
    })
    private static void listPools(Context ctx) {
        if (backendClient == null) {
            ctx.status(503).result("Backend not configured");
            return;
        }

        try {
            List<PoolInfo> pools = backendClient.listPools();
            ctx.json(pools);
        } catch (Exception e) {
            ctx.status(500).result("Error: " + e.getMessage());
        }
    }

    @OpenApi(path = "/api/pools/{poolId}/status", methods = HttpMethod.GET, summary = "Get Pool Status", tags = {"Pools"}, responses = {
            @OpenApiResponse(status = "200", content = @OpenApiContent(from = PoolStatus.class))
    })
    private static void getPoolStatus(Context ctx) {
        if (backendClient == null) {
            ctx.status(503).result("Backend not configured");
            return;
        }

        String poolId = ctx.pathParam("poolId");
        try {
            PoolStatus status = backendClient.getPoolStatus(poolId);
            ctx.json(status);
        } catch (Exception e) {
            ctx.status(500).result("Error: " + e.getMessage());
        }
    }

    @OpenApi(path = "/api/pools/{poolId}/records", methods = HttpMethod.GET, summary = "Get Pool Records", tags = {"Pools"}, 
            queryParams = {@OpenApiParam(name = "page", description = "Page number (0-indexed)", required = false)},
            responses = {
                @OpenApiResponse(status = "200", content = @OpenApiContent(from = PoolRecord[].class))
    })
    private static void getPoolRecords(Context ctx) {
        if (backendClient == null) {
            ctx.status(503).result("Backend not configured");
            return;
        }

        String poolId = ctx.pathParam("poolId");
        int page = ctx.queryParamAsClass("page", Integer.class).getOrDefault(0);
        
        try {
            List<PoolRecord> records = backendClient.getPoolRecords(poolId, page);
            ctx.json(records);
        } catch (Exception e) {
            ctx.status(500).result("Error: " + e.getMessage());
        }
    }

    public record HealthResponse(boolean connected, String message) {}
}
