package com.tcn.sati.core.route;

import com.tcn.sati.infra.gate.GateClient;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.openapi.HttpMethod;
import io.javalin.openapi.OpenApi;
import io.javalin.openapi.OpenApiContent;
import io.javalin.openapi.OpenApiResponse;

/**
 * Routes for Gate/gRPC health checks.
 */
public class GateRoutes {

    private static GateClient gateClient;

    public static void register(Javalin app, GateClient client) {
        gateClient = client;
        app.get("/api/gate/check", GateRoutes::checkConnection);
    }

    @OpenApi(path = "/api/gate/check", methods = HttpMethod.GET, summary = "Check Gate Connection", responses = {
            @OpenApiResponse(status = "200", content = @OpenApiContent(from = CheckResponse.class))
    })
    private static void checkConnection(Context ctx) {
        if (gateClient == null) {
            ctx.status(503).json(new CheckResponse(false, "GateClient not configured"));
            return;
        }

        boolean connected = gateClient.checkConnection();
        ctx.json(new CheckResponse(connected, connected ? "Connected" : "Failed to connect"));
    }

    public record CheckResponse(boolean connected, String message) {
    }
}
