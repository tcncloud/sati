package com.tcn.sati.core.route;

import com.tcn.sati.core.service.AgentService;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.openapi.*;

/**
 * Routes for agent operations — thin HTTP layer, delegates to AgentService.
 */
public class AgentRoutes {
    private static AgentService service;

    public static void register(Javalin app, AgentService svc) {
        service = svc;

        app.get("/api/agents", AgentRoutes::listAgents);
        app.post("/api/agents", AgentRoutes::upsertAgent);

        app.get("/api/agents/{partnerAgentId}/state", AgentRoutes::getAgentState);
        app.put("/api/agents/{partnerAgentId}/state/{state}", AgentRoutes::updateAgentState);

        app.post("/api/agents/{partnerAgentId}/dial", AgentRoutes::dial);

        app.get("/api/agents/{partnerAgentId}/recording", AgentRoutes::getRecording);
        app.put("/api/agents/{partnerAgentId}/recording/{status}", AgentRoutes::setRecording);

        app.get("/api/agents/{partnerAgentId}/pausecodes", AgentRoutes::getPauseCodes);

        app.put("/api/agents/{partnerAgentId}/simplehold", AgentRoutes::simpleHold);
        app.put("/api/agents/{partnerAgentId}/simpleunhold", AgentRoutes::simpleUnhold);
        app.put("/api/agents/{partnerAgentId}/mute", AgentRoutes::mute);
        app.put("/api/agents/{partnerAgentId}/unmute", AgentRoutes::unmute);

        app.put("/api/agents/{partnerAgentId}/callresponse", AgentRoutes::callResponse);
    }

    // --- Request DTOs (just for JSON parsing) ---

    public record UpsertRequest(String username, String firstName, String lastName,
            String partnerAgentId, String password) {
    }

    public record DialRequest(String phoneNumber, String callerId, String poolId,
            String recordId, String rulesetName,
            Boolean skipComplianceChecks, Boolean recordCall) {
    }

    public record CallResponseRequest(String callSid, String callType,
            Long currentSessionId, String key, String value) {
    }

    // --- Handlers ---

    @OpenApi(path = "/api/agents", methods = HttpMethod.GET, summary = "List Agents", tags = { "Agents" })
    private static void listAgents(Context ctx) {
        Boolean loggedIn = ctx.queryParam("loggedIn") != null
                ? Boolean.parseBoolean(ctx.queryParam("loggedIn"))
                : null;
        String state = ctx.queryParam("state");
        ctx.json(service.listAgents(loggedIn, state));
    }

    @OpenApi(path = "/api/agents", methods = HttpMethod.POST, summary = "Create/Upsert Agent", tags = {
            "Agents" }, requestBody = @OpenApiRequestBody(content = @OpenApiContent(from = UpsertRequest.class)))
    private static void upsertAgent(Context ctx) {
        var body = ctx.bodyAsClass(UpsertRequest.class);
        ctx.json(service.upsertAgent(body.username(), body.firstName(),
                body.lastName(), body.partnerAgentId(), body.password()));
    }

    @OpenApi(path = "/api/agents/{partnerAgentId}/state", methods = HttpMethod.GET, summary = "Get Agent State", tags = {
            "Agents" })
    private static void getAgentState(Context ctx) {
        ctx.json(service.getAgentState(ctx.pathParam("partnerAgentId")));
    }

    @OpenApi(path = "/api/agents/{partnerAgentId}/state/{state}", methods = HttpMethod.PUT, summary = "Update Agent State", tags = {
            "Agents" })
    private static void updateAgentState(Context ctx) {
        String reason = ctx.queryParam("reason");
        ctx.json(service.updateAgentState(ctx.pathParam("partnerAgentId"), ctx.pathParam("state"), reason));
    }

    @OpenApi(path = "/api/agents/{partnerAgentId}/dial", methods = HttpMethod.POST, summary = "Dial", tags = {
            "Agents" }, requestBody = @OpenApiRequestBody(content = @OpenApiContent(from = DialRequest.class)))
    private static void dial(Context ctx) {
        var body = ctx.bodyAsClass(DialRequest.class);
        ctx.json(service.dial(ctx.pathParam("partnerAgentId"),
                body.phoneNumber(), body.callerId(), body.poolId(),
                body.recordId(), body.rulesetName(),
                body.skipComplianceChecks(), body.recordCall()));
    }

    @OpenApi(path = "/api/agents/{partnerAgentId}/recording", methods = HttpMethod.GET, summary = "Get Recording Status", tags = {
            "Agents" })
    private static void getRecording(Context ctx) {
        ctx.json(service.getRecordingStatus(ctx.pathParam("partnerAgentId")));
    }

    @OpenApi(path = "/api/agents/{partnerAgentId}/recording/{status}", methods = HttpMethod.PUT, summary = "Start/Stop Recording", tags = {
            "Agents" })
    private static void setRecording(Context ctx) {
        String status = ctx.pathParam("status").toLowerCase();
        if ("start".equals(status)) {
            ctx.json(service.startRecording(ctx.pathParam("partnerAgentId")));
        } else if ("stop".equals(status)) {
            ctx.json(service.stopRecording(ctx.pathParam("partnerAgentId")));
        } else {
            ctx.status(400).json(java.util.Map.of("error", "Invalid status: " + status));
        }
    }

    @OpenApi(path = "/api/agents/{partnerAgentId}/pausecodes", methods = HttpMethod.GET, summary = "List Pause Codes", tags = {
            "Agents" })
    private static void getPauseCodes(Context ctx) {
        ctx.json(service.listPauseCodes(ctx.pathParam("partnerAgentId")));
    }

    @OpenApi(path = "/api/agents/{partnerAgentId}/simplehold", methods = HttpMethod.PUT, summary = "Put Call on Hold", tags = {
            "Agents" })
    private static void simpleHold(Context ctx) {
        ctx.json(service.simpleHold(ctx.pathParam("partnerAgentId")));
    }

    @OpenApi(path = "/api/agents/{partnerAgentId}/simpleunhold", methods = HttpMethod.PUT, summary = "Take Call Off Hold", tags = {
            "Agents" })
    private static void simpleUnhold(Context ctx) {
        ctx.json(service.simpleUnhold(ctx.pathParam("partnerAgentId")));
    }

    @OpenApi(path = "/api/agents/{partnerAgentId}/mute", methods = HttpMethod.PUT, summary = "Mute Agent", tags = {
            "Agents" })
    private static void mute(Context ctx) {
        ctx.json(service.mute(ctx.pathParam("partnerAgentId")));
    }

    @OpenApi(path = "/api/agents/{partnerAgentId}/unmute", methods = HttpMethod.PUT, summary = "Unmute Agent", tags = {
            "Agents" })
    private static void unmute(Context ctx) {
        ctx.json(service.unmute(ctx.pathParam("partnerAgentId")));
    }

    @OpenApi(path = "/api/agents/{partnerAgentId}/callresponse", methods = HttpMethod.PUT, summary = "Add Agent Call Response", tags = {
            "Agents" }, requestBody = @OpenApiRequestBody(content = @OpenApiContent(from = CallResponseRequest.class)))
    private static void callResponse(Context ctx) {
        var body = ctx.bodyAsClass(CallResponseRequest.class);
        ctx.json(service.addCallResponse(ctx.pathParam("partnerAgentId"),
                body.callSid(), body.currentSessionId(), body.key(), body.value()));
    }
}
