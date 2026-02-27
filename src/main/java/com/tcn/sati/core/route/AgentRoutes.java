package com.tcn.sati.core.route;

import com.tcn.sati.core.service.AgentService;
import com.tcn.sati.core.service.dto.AgentDto;
import com.tcn.sati.core.service.dto.SuccessResult;
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

        // --- Handlers ---

        @OpenApi(path = "/api/agents", methods = HttpMethod.GET, summary = "List Agents", tags = {
                        "Agents" }, queryParams = {
                                        @OpenApiParam(name = "loggedIn", type = Boolean.class, description = "Filter by login status"),
                                        @OpenApiParam(name = "state", description = "Filter by agent state")
                        }, responses = @OpenApiResponse(status = "200", content = @OpenApiContent(from = AgentDto.AgentInfo[].class)))
        private static void listAgents(Context ctx) {
                var request = new AgentDto.ListAgentsRequest();
                request.loggedIn = ctx.queryParam("loggedIn") != null
                                ? Boolean.parseBoolean(ctx.queryParam("loggedIn"))
                                : null;
                request.state = ctx.queryParam("state");
                ctx.json(service.listAgents(request));
        }

        @OpenApi(path = "/api/agents", methods = HttpMethod.POST, summary = "Create/Upsert Agent", tags = {
                        "Agents" }, requestBody = @OpenApiRequestBody(content = @OpenApiContent(from = AgentDto.UpsertAgentRequest.class)), responses = @OpenApiResponse(status = "200", content = @OpenApiContent(from = AgentDto.AgentInfo.class)))
        private static void upsertAgent(Context ctx) {
                var body = ctx.bodyAsClass(AgentDto.UpsertAgentRequest.class);
                ctx.json(service.upsertAgent(body));
        }

        @OpenApi(path = "/api/agents/{partnerAgentId}/state", methods = HttpMethod.GET, summary = "Get Agent State", tags = {
                        "Agents" }, pathParams = @OpenApiParam(name = "partnerAgentId", required = true), responses = @OpenApiResponse(status = "200", content = @OpenApiContent(from = AgentDto.AgentStateInfo.class)))
        private static void getAgentState(Context ctx) {
                ctx.json(service.getAgentState(ctx.pathParam("partnerAgentId")));
        }

        @OpenApi(path = "/api/agents/{partnerAgentId}/state/{state}", methods = HttpMethod.PUT, summary = "Update Agent State", tags = {
                        "Agents" }, pathParams = {
                                        @OpenApiParam(name = "partnerAgentId", required = true),
                                        @OpenApiParam(name = "state", required = true, description = "Target state (e.g. READY, PAUSED)")
                        }, queryParams = @OpenApiParam(name = "reason", description = "Pause reason code"), responses = @OpenApiResponse(status = "200", content = @OpenApiContent(from = SuccessResult.class)))
        private static void updateAgentState(Context ctx) {
                String reason = ctx.queryParam("reason");
                ctx.json(service.updateAgentState(ctx.pathParam("partnerAgentId"), ctx.pathParam("state"), reason));
        }

        @OpenApi(path = "/api/agents/{partnerAgentId}/dial", methods = HttpMethod.POST, summary = "Dial", tags = {
                        "Agents" }, pathParams = @OpenApiParam(name = "partnerAgentId", required = true), requestBody = @OpenApiRequestBody(content = @OpenApiContent(from = AgentDto.DialRequest.class)), responses = @OpenApiResponse(status = "200", content = @OpenApiContent(from = AgentDto.DialResult.class)))
        private static void dial(Context ctx) {
                var body = ctx.bodyAsClass(AgentDto.DialRequest.class);
                body.partnerAgentId = ctx.pathParam("partnerAgentId");
                ctx.json(service.dial(body));
        }

        @OpenApi(path = "/api/agents/{partnerAgentId}/recording", methods = HttpMethod.GET, summary = "Get Recording Status", tags = {
                        "Agents" }, pathParams = @OpenApiParam(name = "partnerAgentId", required = true), responses = @OpenApiResponse(status = "200", content = @OpenApiContent(from = AgentDto.RecordingStatus.class)))
        private static void getRecording(Context ctx) {
                ctx.json(service.getRecordingStatus(ctx.pathParam("partnerAgentId")));
        }

        @OpenApi(path = "/api/agents/{partnerAgentId}/recording/{status}", methods = HttpMethod.PUT, summary = "Start/Stop Recording", tags = {
                        "Agents" }, pathParams = {
                                        @OpenApiParam(name = "partnerAgentId", required = true),
                                        @OpenApiParam(name = "status", required = true, description = "start or stop")
                        }, responses = @OpenApiResponse(status = "200", content = @OpenApiContent(from = AgentDto.RecordingStatus.class)))
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
                        "Agents" }, pathParams = @OpenApiParam(name = "partnerAgentId", required = true), responses = @OpenApiResponse(status = "200"))
        private static void getPauseCodes(Context ctx) {
                ctx.json(service.listPauseCodes(ctx.pathParam("partnerAgentId")));
        }

        @OpenApi(path = "/api/agents/{partnerAgentId}/simplehold", methods = HttpMethod.PUT, summary = "Put Call on Hold", tags = {
                        "Agents" }, pathParams = @OpenApiParam(name = "partnerAgentId", required = true), responses = @OpenApiResponse(status = "200", content = @OpenApiContent(from = SuccessResult.class)))
        private static void simpleHold(Context ctx) {
                ctx.json(service.simpleHold(ctx.pathParam("partnerAgentId")));
        }

        @OpenApi(path = "/api/agents/{partnerAgentId}/simpleunhold", methods = HttpMethod.PUT, summary = "Take Call Off Hold", tags = {
                        "Agents" }, pathParams = @OpenApiParam(name = "partnerAgentId", required = true), responses = @OpenApiResponse(status = "200", content = @OpenApiContent(from = SuccessResult.class)))
        private static void simpleUnhold(Context ctx) {
                ctx.json(service.simpleUnhold(ctx.pathParam("partnerAgentId")));
        }

        @OpenApi(path = "/api/agents/{partnerAgentId}/mute", methods = HttpMethod.PUT, summary = "Mute Agent", tags = {
                        "Agents" }, pathParams = @OpenApiParam(name = "partnerAgentId", required = true), responses = @OpenApiResponse(status = "200", content = @OpenApiContent(from = SuccessResult.class)))
        private static void mute(Context ctx) {
                ctx.json(service.mute(ctx.pathParam("partnerAgentId")));
        }

        @OpenApi(path = "/api/agents/{partnerAgentId}/unmute", methods = HttpMethod.PUT, summary = "Unmute Agent", tags = {
                        "Agents" }, pathParams = @OpenApiParam(name = "partnerAgentId", required = true), responses = @OpenApiResponse(status = "200", content = @OpenApiContent(from = SuccessResult.class)))
        private static void unmute(Context ctx) {
                ctx.json(service.unmute(ctx.pathParam("partnerAgentId")));
        }

        @OpenApi(path = "/api/agents/{partnerAgentId}/callresponse", methods = HttpMethod.PUT, summary = "Add Agent Call Response", tags = {
                        "Agents" }, pathParams = @OpenApiParam(name = "partnerAgentId", required = true), requestBody = @OpenApiRequestBody(content = @OpenApiContent(from = AgentDto.CallResponseRequest.class)), responses = @OpenApiResponse(status = "200", content = @OpenApiContent(from = SuccessResult.class)))
        private static void callResponse(Context ctx) {
                var body = ctx.bodyAsClass(AgentDto.CallResponseRequest.class);
                body.partnerAgentId = ctx.pathParam("partnerAgentId");
                ctx.json(service.addCallResponse(body));
        }
}
