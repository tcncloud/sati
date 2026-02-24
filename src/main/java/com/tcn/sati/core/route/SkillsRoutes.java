package com.tcn.sati.core.route;

import com.tcn.sati.core.service.SkillsService;
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

        public record AssignSkillRequest(String skillId, int proficiency) {
        }

        public record UnassignSkillRequest(String skillId) {
        }

        @OpenApi(path = "/api/skills", methods = HttpMethod.GET, summary = "List All Skills", tags = { "Skills" })
        private static void listSkills(Context ctx) {
                ctx.json(service.listSkills());
        }

        @OpenApi(path = "/api/skills/agents/{partnerAgentId}", methods = HttpMethod.GET, summary = "List Agent Skills", tags = {
                        "Skills" })
        private static void listAgentSkills(Context ctx) {
                ctx.json(service.listAgentSkills(ctx.pathParam("partnerAgentId")));
        }

        @OpenApi(path = "/api/skills/agents/{partnerAgentId}/assign", methods = HttpMethod.POST, summary = "Assign Skill to Agent", tags = {
                        "Skills" }, requestBody = @OpenApiRequestBody(content = @OpenApiContent(from = AssignSkillRequest.class)))
        private static void assignSkill(Context ctx) {
                var body = ctx.bodyAsClass(AssignSkillRequest.class);
                ctx.json(service.assignSkill(ctx.pathParam("partnerAgentId"), body.skillId(), body.proficiency()));
        }

        @OpenApi(path = "/api/skills/agents/{partnerAgentId}/unassign", methods = HttpMethod.POST, summary = "Unassign Skill from Agent", tags = {
                        "Skills" }, requestBody = @OpenApiRequestBody(content = @OpenApiContent(from = UnassignSkillRequest.class)))
        private static void unassignSkill(Context ctx) {
                var body = ctx.bodyAsClass(UnassignSkillRequest.class);
                ctx.json(service.unassignSkill(ctx.pathParam("partnerAgentId"), body.skillId()));
        }
}
