package com.tcn.sati.core.route;

import com.tcn.sati.core.service.SkillsService;
import com.tcn.sati.core.service.dto.SkillDto;
import com.tcn.sati.core.service.dto.SuccessResult;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.openapi.*;

/**
 * Routes for agent skill management — delegates to SkillsService.
 */
public class SkillsRoutes {
        private static SkillsService service;

        public static void register(Javalin app, SkillsService svc) {
                service = svc;

                app.get("/api/skills", SkillsRoutes::listSkills);
                app.get("/api/skills/agents/{partnerAgentId}", SkillsRoutes::listAgentSkills);
                app.post("/api/skills/agents/{partnerAgentId}/assign", SkillsRoutes::assignSkill);
                app.post("/api/skills/agents/{partnerAgentId}/unassign", SkillsRoutes::unassignSkill);
        }

        @OpenApi(path = "/api/skills", methods = HttpMethod.GET, summary = "List All Skills", tags = {
                        "Skills" }, responses = @OpenApiResponse(status = "200", content = @OpenApiContent(from = SkillDto.SkillInfo[].class)))
        private static void listSkills(Context ctx) {
                ctx.json(service.listSkills());
        }

        @OpenApi(path = "/api/skills/agents/{partnerAgentId}", methods = HttpMethod.GET, summary = "List Agent Skills", tags = {
                        "Skills" }, pathParams = @OpenApiParam(name = "partnerAgentId", required = true), responses = @OpenApiResponse(status = "200", content = @OpenApiContent(from = SkillDto.SkillInfo[].class)))
        private static void listAgentSkills(Context ctx) {
                ctx.json(service.listAgentSkills(ctx.pathParam("partnerAgentId")));
        }

        @OpenApi(path = "/api/skills/agents/{partnerAgentId}/assign", methods = HttpMethod.POST, summary = "Assign Skill to Agent", tags = {
                        "Skills" }, pathParams = @OpenApiParam(name = "partnerAgentId", required = true), requestBody = @OpenApiRequestBody(content = @OpenApiContent(from = SkillDto.AssignSkillRequest.class)), responses = @OpenApiResponse(status = "200", content = @OpenApiContent(from = SuccessResult.class)))
        private static void assignSkill(Context ctx) {
                var body = ctx.bodyAsClass(SkillDto.AssignSkillRequest.class);
                body.partnerAgentId = ctx.pathParam("partnerAgentId");
                ctx.json(service.assignSkill(body));
        }

        @OpenApi(path = "/api/skills/agents/{partnerAgentId}/unassign", methods = HttpMethod.POST, summary = "Unassign Skill from Agent", tags = {
                        "Skills" }, pathParams = @OpenApiParam(name = "partnerAgentId", required = true), requestBody = @OpenApiRequestBody(content = @OpenApiContent(from = SkillDto.UnassignSkillRequest.class)), responses = @OpenApiResponse(status = "200", content = @OpenApiContent(from = SuccessResult.class)))
        private static void unassignSkill(Context ctx) {
                var body = ctx.bodyAsClass(SkillDto.UnassignSkillRequest.class);
                body.partnerAgentId = ctx.pathParam("partnerAgentId");
                ctx.json(service.unassignSkill(body));
        }
}
