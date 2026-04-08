package com.tcn.sati.core.route;

import com.tcn.sati.core.service.*;
import com.tcn.sati.core.service.dto.*;
import com.tcn.sati.core.tenant.TenantContext;
import com.tcn.sati.core.tenant.TenantManager;
import com.tcn.sati.infra.backend.TenantBackendClient.*;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.openapi.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Org-scoped multi-tenant routes. All paths use the
 * {@code /api/orgs/{tenantKey}/...}
 * prefix, matching API patterns.
 *
 * <p>
 * Each handler reads the {@link TenantContext} from
 * {@code ctx.attribute("tenant")},
 * which is set by the before-filter registered here.
 */
public class OrgRoutes {

    private static TenantManager tenantManager;

    private static final String P = "/api/orgs/{tenantKey}";

    public static void register(Javalin app, TenantManager manager) {
        tenantManager = manager;

        // --- Tenant list (no tenantKey required) ---
        app.get("/api/orgs", OrgRoutes::listOrgs);

        // --- Before filter: resolve tenant for all org-scoped routes ---
        app.before(P + "/*", ctx -> {
            String tenantKey = ctx.pathParam("tenantKey");
            TenantContext tenant = tenantManager.getTenant(tenantKey);
            if (tenant == null) {
                ctx.status(404).json(Map.of("error", "Tenant not found: " + tenantKey));
                return;
            }
            ctx.attribute("tenant", tenant);
        });

        // --- Status ---
        app.get(P + "/status", OrgRoutes::getStatus);

        // --- Backend / Pools ---
        app.get(P + "/backend/health", OrgRoutes::backendHealth);
        app.get(P + "/pools", OrgRoutes::listPools);
        app.get(P + "/pools/{poolId}/status", OrgRoutes::poolStatus);
        app.get(P + "/pools/{poolId}/records", OrgRoutes::poolRecords);

        // --- Gate ---
        app.get(P + "/gate/check", OrgRoutes::gateCheck);

        // --- Agents ---
        app.get(P + "/agents", OrgRoutes::listAgents);
        app.post(P + "/agents", OrgRoutes::upsertAgent);
        app.get(P + "/agents/{partnerAgentId}/state", OrgRoutes::getAgentState);
        app.put(P + "/agents/{partnerAgentId}/state/{state}", OrgRoutes::updateAgentState);
        app.put(P + "/agents/{partnerAgentId}/dial", OrgRoutes::dial);
        app.get(P + "/agents/{partnerAgentId}/recording", OrgRoutes::getRecording);
        app.put(P + "/agents/{partnerAgentId}/recording/{status}", OrgRoutes::setRecording);
        app.get(P + "/agents/{partnerAgentId}/pausecodes", OrgRoutes::getPauseCodes);
        app.put(P + "/agents/{partnerAgentId}/simplehold", OrgRoutes::simpleHold);
        app.put(P + "/agents/{partnerAgentId}/simpleunhold", OrgRoutes::simpleUnhold);
        app.put(P + "/agents/{partnerAgentId}/mute", OrgRoutes::mute);
        app.put(P + "/agents/{partnerAgentId}/unmute", OrgRoutes::unmute);
        app.put(P + "/agents/{partnerAgentId}/callresponse", OrgRoutes::callResponse);

        // --- Transfer ---
        app.post(P + "/transfer", OrgRoutes::transfer);
        app.put(P + "/transfer/{partnerAgentId}/hold-caller", OrgRoutes::holdCaller);
        app.put(P + "/transfer/{partnerAgentId}/unhold-caller", OrgRoutes::unholdCaller);
        app.put(P + "/transfer/{partnerAgentId}/hold-agent", OrgRoutes::holdAgent);
        app.put(P + "/transfer/{partnerAgentId}/unhold-agent", OrgRoutes::unholdAgent);

        // --- Scrub Lists ---
        app.get(P + "/scrublists", OrgRoutes::listScrubLists);
        app.post(P + "/scrublists/{scrubListId}", OrgRoutes::upsertScrubEntry);
        app.delete(P + "/scrublists/{scrubListId}/delete/{content}", OrgRoutes::deleteScrubEntry);

        // --- Skills ---
        app.get(P + "/skills", OrgRoutes::listSkills);
        app.get(P + "/skills/agents/{partnerAgentId}", OrgRoutes::listAgentSkills);
        app.post(P + "/skills/agents/{partnerAgentId}/assign", OrgRoutes::assignSkill);
        app.post(P + "/skills/agents/{partnerAgentId}/unassign", OrgRoutes::unassignSkill);

        // --- NCL Rulesets ---
        app.get(P + "/nclrulesets/names", OrgRoutes::listNCLNames);

        // --- Voice Recordings ---
        app.get(P + "/voice-recordings", OrgRoutes::searchRecordings);
        app.get(P + "/voice-recordings/download-link", OrgRoutes::downloadLink);
        app.get(P + "/voice-recordings/list-search-options", OrgRoutes::listSearchOptions);
        app.post(P + "/voice-recordings/label-recording", OrgRoutes::labelRecording);

        // --- Journey Buffer ---
        app.post(P + "/journey-buffer/add-record", OrgRoutes::addJourneyRecord);
    }

    // ========== Helpers ==========

    private static TenantContext tenant(Context ctx) {
        return ctx.attribute("tenant");
    }

    private static AgentService agentSvc(Context ctx) {
        return new AgentService(tenant(ctx).getGateClient());
    }

    private static TransferService transferSvc(Context ctx) {
        return new TransferService(tenant(ctx).getGateClient());
    }

    // ========== Tenant Management ==========

    @OpenApi(path = "/api/orgs", methods = HttpMethod.GET, summary = "List Tenants", tags = {
            "Tenants" }, responses = @OpenApiResponse(status = "200"))
    private static void listOrgs(Context ctx) {
        ctx.json(tenantManager.getAllStatus());
    }

    @OpenApi(path = P + "/status", methods = HttpMethod.GET, summary = "Get Tenant Status", tags = {
            "Tenants" }, pathParams = @OpenApiParam(name = "tenantKey", required = true), responses = @OpenApiResponse(status = "200"))
    private static void getStatus(Context ctx) {
        TenantContext t = tenant(ctx);
        if (t != null)
            ctx.json(t.getStatus());
    }

    // ========== Backend / Pools ==========

    @OpenApi(path = P + "/backend/health", methods = HttpMethod.GET, summary = "Check Backend Health", tags = {
            "Backend" }, pathParams = @OpenApiParam(name = "tenantKey", required = true), responses = @OpenApiResponse(status = "200", content = @OpenApiContent(from = BackendRoutes.HealthResponse.class)))
    private static void backendHealth(Context ctx) {
        TenantContext t = tenant(ctx);
        if (t == null)
            return;
        boolean ok = t.getBackendClient().isConnected();
        ctx.json(Map.of("connected", ok, "message", ok ? "Connected" : "Failed"));
    }

    @OpenApi(path = P + "/pools", methods = HttpMethod.GET, summary = "List Pools", tags = {
            "Pools" }, pathParams = @OpenApiParam(name = "tenantKey", required = true), responses = @OpenApiResponse(status = "200", content = @OpenApiContent(from = PoolInfo[].class)))
    private static void listPools(Context ctx) {
        TenantContext t = tenant(ctx);
        if (t != null)
            ctx.json(t.getBackendClient().listPools());
    }

    @OpenApi(path = P + "/pools/{poolId}/status", methods = HttpMethod.GET, summary = "Get Pool Status", tags = {
            "Pools" }, pathParams = { @OpenApiParam(name = "tenantKey", required = true),
                    @OpenApiParam(name = "poolId", required = true) }, responses = @OpenApiResponse(status = "200", content = @OpenApiContent(from = PoolStatus.class)))
    private static void poolStatus(Context ctx) {
        TenantContext t = tenant(ctx);
        if (t != null)
            ctx.json(t.getBackendClient().getPoolStatus(ctx.pathParam("poolId")));
    }

    @OpenApi(path = P + "/pools/{poolId}/records", methods = HttpMethod.GET, summary = "Get Pool Records", tags = {
            "Pools" }, pathParams = { @OpenApiParam(name = "tenantKey", required = true),
                    @OpenApiParam(name = "poolId", required = true) }, queryParams = @OpenApiParam(name = "page", description = "Page number (0-indexed)"), responses = @OpenApiResponse(status = "200", content = @OpenApiContent(from = PoolRecord[].class)))
    private static void poolRecords(Context ctx) {
        TenantContext t = tenant(ctx);
        if (t == null)
            return;
        int page = ctx.queryParamAsClass("page", Integer.class).getOrDefault(0);
        ctx.json(t.getBackendClient().getPoolRecords(ctx.pathParam("poolId"), page));
    }

    // ========== Gate ==========

    @OpenApi(path = P + "/gate/check", methods = HttpMethod.GET, summary = "Check Gate Connection", tags = {
            "Gate" }, pathParams = @OpenApiParam(name = "tenantKey", required = true), responses = @OpenApiResponse(status = "200", content = @OpenApiContent(from = GateRoutes.CheckResponse.class)))
    private static void gateCheck(Context ctx) {
        TenantContext t = tenant(ctx);
        if (t == null)
            return;
        var gate = t.getGateClient();
        if (gate == null) {
            ctx.status(503).json(Map.of("connected", false, "message", "Gate not configured"));
        } else {
            boolean ok = gate.checkConnection();
            ctx.json(Map.of("connected", ok, "message", ok ? "Connected" : "Failed"));
        }
    }

    // ========== Agents ==========

    @OpenApi(path = P + "/agents", methods = HttpMethod.GET, summary = "List Agents", tags = {
            "Agents" }, pathParams = @OpenApiParam(name = "tenantKey", required = true), queryParams = {
                    @OpenApiParam(name = "loggedIn", type = Boolean.class, description = "Filter by login status"),
                    @OpenApiParam(name = "state", description = "Filter by agent state"),
                    @OpenApiParam(name = "fetch_recording_status", type = Boolean.class, description = "If true, fetch recording status for each agent (may be expensive)")
            }, responses = @OpenApiResponse(status = "200", content = @OpenApiContent(from = AgentDto.AgentInfo[].class)))
    private static void listAgents(Context ctx) {
        TenantContext t = tenant(ctx);
        if (t == null || t.getGateClient() == null)
            return;
        var req = new AgentDto.ListAgentsRequest();
        req.loggedIn = ctx.queryParam("loggedIn") != null ? Boolean.parseBoolean(ctx.queryParam("loggedIn")) : null;
        req.state = ctx.queryParam("state");
        req.fetchRecordingStatus = "true".equalsIgnoreCase(ctx.queryParam("fetch_recording_status"));
        ctx.json(agentSvc(ctx).listAgents(req));
    }

    @OpenApi(path = P + "/agents", methods = HttpMethod.POST, summary = "Create/Upsert Agent", tags = {
            "Agents" }, pathParams = @OpenApiParam(name = "tenantKey", required = true), requestBody = @OpenApiRequestBody(content = @OpenApiContent(from = AgentDto.UpsertAgentRequest.class)), responses = @OpenApiResponse(status = "200", content = @OpenApiContent(from = AgentDto.AgentInfo.class)))
    private static void upsertAgent(Context ctx) {
        TenantContext t = tenant(ctx);
        if (t == null || t.getGateClient() == null)
            return;
        ctx.json(agentSvc(ctx).upsertAgent(ctx.bodyAsClass(AgentDto.UpsertAgentRequest.class)));
    }

    @OpenApi(path = P
            + "/agents/{partnerAgentId}/state", methods = HttpMethod.GET, summary = "Get Agent State", tags = {
                    "Agents" }, pathParams = { @OpenApiParam(name = "tenantKey", required = true),
                            @OpenApiParam(name = "partnerAgentId", required = true) }, responses = @OpenApiResponse(status = "200", content = @OpenApiContent(from = AgentDto.AgentStateInfo.class)))
    private static void getAgentState(Context ctx) {
        TenantContext t = tenant(ctx);
        if (t == null || t.getGateClient() == null)
            return;
        ctx.json(agentSvc(ctx).getAgentState(ctx.pathParam("partnerAgentId")));
    }

    @OpenApi(path = P
            + "/agents/{partnerAgentId}/state/{state}", methods = HttpMethod.PUT, summary = "Update Agent State", tags = {
                    "Agents" }, pathParams = { @OpenApiParam(name = "tenantKey", required = true),
                            @OpenApiParam(name = "partnerAgentId", required = true),
                            @OpenApiParam(name = "state", required = true, description = "Target state (e.g. READY, PAUSED)") }, requestBody = @OpenApiRequestBody(content = @OpenApiContent(from = AgentDto.PauseCodeReason.class)), responses = @OpenApiResponse(status = "200", content = @OpenApiContent(from = SuccessResult.class)))
    private static void updateAgentState(Context ctx) {
        TenantContext t = tenant(ctx);
        if (t == null || t.getGateClient() == null)
            return;
        String reason = null;
        String body = ctx.body();
        if (body != null && !body.isBlank()) {
            try {
                var parsed = ctx.bodyAsClass(AgentDto.PauseCodeReason.class);
                reason = parsed.reason;
            } catch (Exception e) {
                // Body wasn't valid JSON or didn't have reason — that's fine
            }
        }
        ctx.json(agentSvc(ctx).updateAgentState(
                ctx.pathParam("partnerAgentId"), ctx.pathParam("state"), reason));
    }

    @OpenApi(path = P + "/agents/{partnerAgentId}/dial", methods = HttpMethod.PUT, summary = "Dial", tags = {
            "Agents" }, pathParams = { @OpenApiParam(name = "tenantKey", required = true),
                    @OpenApiParam(name = "partnerAgentId", required = true) }, requestBody = @OpenApiRequestBody(content = @OpenApiContent(from = AgentDto.DialRequest.class)), responses = @OpenApiResponse(status = "200", content = @OpenApiContent(from = AgentDto.DialResult.class)))
    private static void dial(Context ctx) {
        TenantContext t = tenant(ctx);
        if (t == null || t.getGateClient() == null)
            return;
        var body = ctx.bodyAsClass(AgentDto.DialRequest.class);
        ctx.json(agentSvc(ctx).dial(ctx.pathParam("partnerAgentId"), body));
    }

    @OpenApi(path = P
            + "/agents/{partnerAgentId}/recording", methods = HttpMethod.GET, summary = "Get Recording Status", tags = {
                    "Agents" }, pathParams = { @OpenApiParam(name = "tenantKey", required = true),
                            @OpenApiParam(name = "partnerAgentId", required = true) }, responses = @OpenApiResponse(status = "200", content = @OpenApiContent(from = AgentDto.RecordingStatus.class)))
    private static void getRecording(Context ctx) {
        TenantContext t = tenant(ctx);
        if (t == null || t.getGateClient() == null)
            return;
        ctx.json(agentSvc(ctx).getRecordingStatus(ctx.pathParam("partnerAgentId")));
    }

    @OpenApi(path = P
            + "/agents/{partnerAgentId}/recording/{status}", methods = HttpMethod.PUT, summary = "Start/Stop Recording", tags = {
                    "Agents" }, pathParams = { @OpenApiParam(name = "tenantKey", required = true),
                            @OpenApiParam(name = "partnerAgentId", required = true),
                            @OpenApiParam(name = "status", required = true, description = "start or stop") }, responses = @OpenApiResponse(status = "200", content = @OpenApiContent(from = AgentDto.RecordingStatus.class)))
    private static void setRecording(Context ctx) {
        TenantContext t = tenant(ctx);
        if (t == null || t.getGateClient() == null)
            return;
        String status = ctx.pathParam("status").toLowerCase();
        String agentId = ctx.pathParam("partnerAgentId");
        switch (status) {
            case "start", "on", "resume", "true" -> ctx.json(agentSvc(ctx).startRecording(agentId));
            case "stop", "off", "pause", "paused", "false" -> ctx.json(agentSvc(ctx).stopRecording(agentId));
            default -> ctx.status(400).json(Map.of("error", "Invalid status: " + status));
        }
    }

    @OpenApi(path = P
            + "/agents/{partnerAgentId}/pausecodes", methods = HttpMethod.GET, summary = "List Pause Codes", tags = {
                    "Agents" }, pathParams = { @OpenApiParam(name = "tenantKey", required = true),
                            @OpenApiParam(name = "partnerAgentId", required = true) }, responses = @OpenApiResponse(status = "200", content = @OpenApiContent(from = String[].class)))
    private static void getPauseCodes(Context ctx) {
        TenantContext t = tenant(ctx);
        if (t == null || t.getGateClient() == null)
            return;
        ctx.json(agentSvc(ctx).listPauseCodes(ctx.pathParam("partnerAgentId")));
    }

    @OpenApi(path = P
            + "/agents/{partnerAgentId}/simplehold", methods = HttpMethod.PUT, summary = "Put Call on Hold", tags = {
                    "Agents" }, pathParams = { @OpenApiParam(name = "tenantKey", required = true),
                            @OpenApiParam(name = "partnerAgentId", required = true) }, responses = @OpenApiResponse(status = "200", content = @OpenApiContent(from = SuccessResult.class)))
    private static void simpleHold(Context ctx) {
        TenantContext t = tenant(ctx);
        if (t == null || t.getGateClient() == null)
            return;
        ctx.json(agentSvc(ctx).simpleHold(ctx.pathParam("partnerAgentId")));
    }

    @OpenApi(path = P
            + "/agents/{partnerAgentId}/simpleunhold", methods = HttpMethod.PUT, summary = "Take Call Off Hold", tags = {
                    "Agents" }, pathParams = { @OpenApiParam(name = "tenantKey", required = true),
                            @OpenApiParam(name = "partnerAgentId", required = true) }, responses = @OpenApiResponse(status = "200", content = @OpenApiContent(from = SuccessResult.class)))
    private static void simpleUnhold(Context ctx) {
        TenantContext t = tenant(ctx);
        if (t == null || t.getGateClient() == null)
            return;
        ctx.json(agentSvc(ctx).simpleUnhold(ctx.pathParam("partnerAgentId")));
    }

    @OpenApi(path = P + "/agents/{partnerAgentId}/mute", methods = HttpMethod.PUT, summary = "Mute Agent", tags = {
            "Agents" }, pathParams = { @OpenApiParam(name = "tenantKey", required = true),
                    @OpenApiParam(name = "partnerAgentId", required = true) }, responses = @OpenApiResponse(status = "200", content = @OpenApiContent(from = SuccessResult.class)))
    private static void mute(Context ctx) {
        TenantContext t = tenant(ctx);
        if (t == null || t.getGateClient() == null)
            return;
        ctx.json(agentSvc(ctx).mute(ctx.pathParam("partnerAgentId")));
    }

    @OpenApi(path = P + "/agents/{partnerAgentId}/unmute", methods = HttpMethod.PUT, summary = "Unmute Agent", tags = {
            "Agents" }, pathParams = { @OpenApiParam(name = "tenantKey", required = true),
                    @OpenApiParam(name = "partnerAgentId", required = true) }, responses = @OpenApiResponse(status = "200", content = @OpenApiContent(from = SuccessResult.class)))
    private static void unmute(Context ctx) {
        TenantContext t = tenant(ctx);
        if (t == null || t.getGateClient() == null)
            return;
        ctx.json(agentSvc(ctx).unmute(ctx.pathParam("partnerAgentId")));
    }

    @OpenApi(path = P
            + "/agents/{partnerAgentId}/callresponse", methods = HttpMethod.PUT, summary = "Add Agent Call Response", tags = {
                    "Agents" }, pathParams = { @OpenApiParam(name = "tenantKey", required = true),
                            @OpenApiParam(name = "partnerAgentId", required = true) }, requestBody = @OpenApiRequestBody(content = @OpenApiContent(from = AgentDto.CallResponseRequest.class)), responses = @OpenApiResponse(status = "200", content = @OpenApiContent(from = SuccessResult.class)))
    private static void callResponse(Context ctx) {
        TenantContext t = tenant(ctx);
        if (t == null || t.getGateClient() == null)
            return;
        var body = ctx.bodyAsClass(AgentDto.CallResponseRequest.class);
        ctx.json(agentSvc(ctx).addCallResponse(ctx.pathParam("partnerAgentId"), body));
    }

    // ========== Transfer ==========

    @OpenApi(path = P + "/transfer", methods = HttpMethod.POST, summary = "Transfer a Call", tags = {
            "Transfer" }, pathParams = @OpenApiParam(name = "tenantKey", required = true), requestBody = @OpenApiRequestBody(content = @OpenApiContent(from = TransferDto.TransferRequest.class)), responses = {
                    @OpenApiResponse(status = "200", content = @OpenApiContent(from = TransferDto.TransferResponse.class)),
                    @OpenApiResponse(status = "400", description = "Missing required fields")
            })
    private static void transfer(Context ctx) {
        TenantContext t = tenant(ctx);
        if (t == null || t.getGateClient() == null)
            return;
        ctx.json(transferSvc(ctx).executeTransfer(ctx.bodyAsClass(TransferDto.TransferRequest.class)));
    }

    @OpenApi(path = P
            + "/transfer/{partnerAgentId}/hold-caller", methods = HttpMethod.PUT, summary = "Hold Caller During Transfer", tags = {
                    "Transfer" }, pathParams = { @OpenApiParam(name = "tenantKey", required = true),
                            @OpenApiParam(name = "partnerAgentId", required = true) }, responses = @OpenApiResponse(status = "200", content = @OpenApiContent(from = SuccessResult.class)))
    private static void holdCaller(Context ctx) {
        TenantContext t = tenant(ctx);
        if (t == null || t.getGateClient() == null)
            return;
        ctx.json(transferSvc(ctx).holdCaller(ctx.pathParam("partnerAgentId")));
    }

    @OpenApi(path = P
            + "/transfer/{partnerAgentId}/unhold-caller", methods = HttpMethod.PUT, summary = "Unhold Caller During Transfer", tags = {
                    "Transfer" }, pathParams = { @OpenApiParam(name = "tenantKey", required = true),
                            @OpenApiParam(name = "partnerAgentId", required = true) }, responses = @OpenApiResponse(status = "200", content = @OpenApiContent(from = SuccessResult.class)))
    private static void unholdCaller(Context ctx) {
        TenantContext t = tenant(ctx);
        if (t == null || t.getGateClient() == null)
            return;
        ctx.json(transferSvc(ctx).unholdCaller(ctx.pathParam("partnerAgentId")));
    }

    @OpenApi(path = P
            + "/transfer/{partnerAgentId}/hold-agent", methods = HttpMethod.PUT, summary = "Hold Agent During Transfer", tags = {
                    "Transfer" }, pathParams = { @OpenApiParam(name = "tenantKey", required = true),
                            @OpenApiParam(name = "partnerAgentId", required = true) }, responses = @OpenApiResponse(status = "200", content = @OpenApiContent(from = SuccessResult.class)))
    private static void holdAgent(Context ctx) {
        TenantContext t = tenant(ctx);
        if (t == null || t.getGateClient() == null)
            return;
        ctx.json(transferSvc(ctx).holdAgent(ctx.pathParam("partnerAgentId")));
    }

    @OpenApi(path = P
            + "/transfer/{partnerAgentId}/unhold-agent", methods = HttpMethod.PUT, summary = "Unhold Agent During Transfer", tags = {
                    "Transfer" }, pathParams = { @OpenApiParam(name = "tenantKey", required = true),
                            @OpenApiParam(name = "partnerAgentId", required = true) }, responses = @OpenApiResponse(status = "200", content = @OpenApiContent(from = SuccessResult.class)))
    private static void unholdAgent(Context ctx) {
        TenantContext t = tenant(ctx);
        if (t == null || t.getGateClient() == null)
            return;
        ctx.json(transferSvc(ctx).unholdAgent(ctx.pathParam("partnerAgentId")));
    }

    // ========== Scrub Lists ==========

    @OpenApi(path = P + "/scrublists", methods = HttpMethod.GET, summary = "List Scrub Lists", tags = {
            "Scrub Lists" }, pathParams = @OpenApiParam(name = "tenantKey", required = true), responses = @OpenApiResponse(status = "200", content = @OpenApiContent(from = ScrubListDto.ScrubListEntry[].class)))
    private static void listScrubLists(Context ctx) {
        TenantContext t = tenant(ctx);
        if (t == null || t.getGateClient() == null)
            return;
        ctx.json(new ScrubListService(t.getGateClient()).list());
    }

    @OpenApi(path = P
            + "/scrublists/{scrubListId}", methods = HttpMethod.POST, summary = "Upsert Scrub List Entry", tags = {
                    "Scrub Lists" }, pathParams = { @OpenApiParam(name = "tenantKey", required = true),
                            @OpenApiParam(name = "scrubListId", required = true) }, requestBody = @OpenApiRequestBody(content = @OpenApiContent(from = ScrubListDto.UpsertScrubEntryRequest.class)), responses = @OpenApiResponse(status = "200", content = @OpenApiContent(from = SuccessResult.class)))
    private static void upsertScrubEntry(Context ctx) {
        TenantContext t = tenant(ctx);
        if (t == null || t.getGateClient() == null)
            return;
        var body = ctx.bodyAsClass(ScrubListDto.UpsertScrubEntryRequest.class);
        ctx.json(new ScrubListService(t.getGateClient()).upsertEntry(ctx.pathParam("scrubListId"), body));
    }

    @OpenApi(path = P
            + "/scrublists/{scrubListId}/delete/{content}", methods = HttpMethod.DELETE, summary = "Delete Scrub List Entry", tags = {
                    "Scrub Lists" }, pathParams = { @OpenApiParam(name = "tenantKey", required = true),
                            @OpenApiParam(name = "scrubListId", required = true),
                            @OpenApiParam(name = "content", required = true, description = "Content to remove") }, responses = @OpenApiResponse(status = "200", content = @OpenApiContent(from = SuccessResult.class)))
    private static void deleteScrubEntry(Context ctx) {
        TenantContext t = tenant(ctx);
        if (t == null || t.getGateClient() == null)
            return;
        ctx.json(new ScrubListService(t.getGateClient()).deleteEntry(
                ctx.pathParam("scrubListId"), ctx.pathParam("content")));
    }

    // ========== Skills ==========

    @OpenApi(path = P + "/skills", methods = HttpMethod.GET, summary = "List All Skills", tags = {
            "Skills" }, pathParams = @OpenApiParam(name = "tenantKey", required = true), responses = @OpenApiResponse(status = "200", content = @OpenApiContent(from = SkillDto.OrgSkillInfo[].class)))
    private static void listSkills(Context ctx) {
        TenantContext t = tenant(ctx);
        if (t == null || t.getGateClient() == null)
            return;
        List<SkillDto.OrgSkillInfo> result = new SkillsService(t.getGateClient()).listSkills().stream().map(s -> {
            var o = new SkillDto.OrgSkillInfo();
            o.skill_id = s.skillId;
            o.name = s.name;
            o.description = s.description;
            return o;
        }).collect(Collectors.toList());
        ctx.json(result);
    }

    @OpenApi(path = P
            + "/skills/agents/{partnerAgentId}", methods = HttpMethod.GET, summary = "List Agent Skills", tags = {
                    "Skills" }, pathParams = { @OpenApiParam(name = "tenantKey", required = true),
                            @OpenApiParam(name = "partnerAgentId", required = true) }, responses = @OpenApiResponse(status = "200", content = @OpenApiContent(from = SkillDto.OrgAgentSkillInfo[].class)))
    private static void listAgentSkills(Context ctx) {
        TenantContext t = tenant(ctx);
        if (t == null || t.getGateClient() == null)
            return;
        List<SkillDto.OrgAgentSkillInfo> result = new SkillsService(t.getGateClient()).listAgentSkills(ctx.pathParam("partnerAgentId")).stream().map(s -> {
            var o = new SkillDto.OrgAgentSkillInfo();
            o.skill_id = s.skillId;
            o.name = s.name;
            o.description = s.description;
            o.proficiency = s.proficiency;
            return o;
        }).collect(Collectors.toList());
        ctx.json(result);
    }

    @OpenApi(path = P
            + "/skills/agents/{partnerAgentId}/assign", methods = HttpMethod.POST, summary = "Assign Skill to Agent", tags = {
                    "Skills" }, pathParams = { @OpenApiParam(name = "tenantKey", required = true),
                            @OpenApiParam(name = "partnerAgentId", required = true) }, requestBody = @OpenApiRequestBody(content = @OpenApiContent(from = SkillDto.AssignSkillRequest.class)), responses = @OpenApiResponse(status = "200", content = @OpenApiContent(from = SuccessResult.class)))
    private static void assignSkill(Context ctx) {
        TenantContext t = tenant(ctx);
        if (t == null || t.getGateClient() == null)
            return;
        var body = ctx.bodyAsClass(SkillDto.AssignSkillRequest.class);
        ctx.json(new SkillsService(t.getGateClient()).assignSkill(ctx.pathParam("partnerAgentId"), body));
    }

    @OpenApi(path = P
            + "/skills/agents/{partnerAgentId}/unassign", methods = HttpMethod.POST, summary = "Unassign Skill from Agent", tags = {
                    "Skills" }, pathParams = { @OpenApiParam(name = "tenantKey", required = true),
                            @OpenApiParam(name = "partnerAgentId", required = true) }, requestBody = @OpenApiRequestBody(content = @OpenApiContent(from = SkillDto.UnassignSkillRequest.class)), responses = @OpenApiResponse(status = "200", content = @OpenApiContent(from = SuccessResult.class)))
    private static void unassignSkill(Context ctx) {
        TenantContext t = tenant(ctx);
        if (t == null || t.getGateClient() == null)
            return;
        var body = ctx.bodyAsClass(SkillDto.UnassignSkillRequest.class);
        ctx.json(new SkillsService(t.getGateClient()).unassignSkill(ctx.pathParam("partnerAgentId"), body));
    }

    // ========== NCL Rulesets ==========

    @OpenApi(path = P + "/nclrulesets/names", methods = HttpMethod.GET, summary = "List NCL Ruleset Names", tags = {
            "NCL Rulesets" }, pathParams = @OpenApiParam(name = "tenantKey", required = true), responses = @OpenApiResponse(status = "200", content = @OpenApiContent(from = String[].class)))
    private static void listNCLNames(Context ctx) {
        TenantContext t = tenant(ctx);
        if (t == null || t.getGateClient() == null)
            return;
        ctx.json(new NCLRulesetService(t.getGateClient()).listNames());
    }

    // ========== Voice Recordings ==========

    @OpenApi(path = P + "/voice-recordings", methods = HttpMethod.GET, summary = "Search Voice Recordings", tags = {
            "Voice Recordings" }, pathParams = @OpenApiParam(name = "tenantKey", required = true), queryParams = @OpenApiParam(name = "searchOption", description = "Format: field,operator,value", required = true), responses = @OpenApiResponse(status = "200", content = @OpenApiContent(from = VoiceRecordingDto.RecordingInfo[].class)))
    private static void searchRecordings(Context ctx) {
        TenantContext t = tenant(ctx);
        if (t == null || t.getGateClient() == null)
            return;
        var searchOptions = ctx.queryParams("searchOption");
        if (searchOptions == null || searchOptions.isEmpty()) {
            ctx.status(400).json(Map.of("error", "searchOption required"));
            return;
        }
        var req = new VoiceRecordingDto.SearchRecordingsRequest();
        req.searchOptions = searchOptions;
        ctx.json(new VoiceRecordingService(t.getGateClient()).search(req));
    }

    @OpenApi(path = P
            + "/voice-recordings/download-link", methods = HttpMethod.GET, summary = "Get Recording Download Link", tags = {
                    "Voice Recordings" }, pathParams = @OpenApiParam(name = "tenantKey", required = true), queryParams = {
                            @OpenApiParam(name = "recordingId", required = true), @OpenApiParam(name = "startOffset"),
                            @OpenApiParam(name = "endOffset") }, responses = @OpenApiResponse(status = "200", content = @OpenApiContent(from = VoiceRecordingDto.DownloadLink.class)))
    private static void downloadLink(Context ctx) {
        TenantContext t = tenant(ctx);
        if (t == null || t.getGateClient() == null)
            return;
        String recId = ctx.queryParam("recordingId");
        if (recId == null || recId.isBlank()) {
            ctx.status(400).json(Map.of("error", "recordingId required"));
            return;
        }
        var req = new VoiceRecordingDto.DownloadLinkRequest();
        req.recordingId = recId;
        req.startOffset = ctx.queryParam("startOffset");
        req.endOffset = ctx.queryParam("endOffset");
        ctx.json(new VoiceRecordingService(t.getGateClient()).getDownloadLink(req));
    }

    @OpenApi(path = P
            + "/voice-recordings/list-search-options", methods = HttpMethod.GET, summary = "List Searchable Recording Fields", tags = {
                    "Voice Recordings" }, pathParams = @OpenApiParam(name = "tenantKey", required = true), responses = @OpenApiResponse(status = "200"))
    private static void listSearchOptions(Context ctx) {
        TenantContext t = tenant(ctx);
        if (t == null || t.getGateClient() == null)
            return;
        ctx.json(new VoiceRecordingService(t.getGateClient()).listSearchableFields());
    }

    @OpenApi(path = P
            + "/voice-recordings/label-recording", methods = HttpMethod.POST, summary = "Create Recording Label", tags = {
                    "Voice Recordings" }, pathParams = @OpenApiParam(name = "tenantKey", required = true), requestBody = @OpenApiRequestBody(content = @OpenApiContent(from = VoiceRecordingDto.CreateLabelRequest.class)), responses = @OpenApiResponse(status = "200", content = @OpenApiContent(from = SuccessResult.class)))
    private static void labelRecording(Context ctx) {
        TenantContext t = tenant(ctx);
        if (t == null || t.getGateClient() == null)
            return;
        ctx.json(new VoiceRecordingService(t.getGateClient()).createLabel(
                ctx.bodyAsClass(VoiceRecordingDto.CreateLabelRequest.class)));
    }

    // ========== Journey Buffer ==========

    @OpenApi(path = P
            + "/journey-buffer/add-record", methods = HttpMethod.POST, summary = "Add Record to Journey Buffer", tags = {
                    "Journey Buffer" }, pathParams = @OpenApiParam(name = "tenantKey", required = true), requestBody = @OpenApiRequestBody(content = @OpenApiContent(from = JourneyBufferDto.AddRecordRequest.class)), responses = @OpenApiResponse(status = "200", content = @OpenApiContent(from = JourneyBufferDto.AddRecordResponse.class)))
    private static void addJourneyRecord(Context ctx) {
        TenantContext t = tenant(ctx);
        if (t == null || t.getGateClient() == null)
            return;
        ctx.json(new JourneyBufferService(t.getGateClient()).addRecord(
                ctx.bodyAsClass(JourneyBufferDto.AddRecordRequest.class)));
    }
}
