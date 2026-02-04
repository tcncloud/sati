package com.tcn.sati.core.route;

import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.openapi.*;
import java.util.Map;
import com.tcn.sati.core.service.AgentService;

/**
 * Routes for agent operations.
 */
public class AgentRoutes {

    private static AgentService agentService;

    public static void register(Javalin app, AgentService service) {
        agentService = service;
        app.get("/api/agent/{id}/status", AgentRoutes::handleGetAgentStatus);
    }

    @OpenApi(path = "/api/agent/{id}/status", methods = HttpMethod.GET, summary = "Get Agent Status", tags = {
            "Agent" })
    private static void handleGetAgentStatus(Context ctx) {
        String agentId = ctx.pathParam("id");
        Map<String, String> result = agentService.getAgentStatus(agentId);
        ctx.json(result);
    }
}
