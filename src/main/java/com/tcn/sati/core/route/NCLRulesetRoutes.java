package com.tcn.sati.core.route;

import com.tcn.sati.core.service.NCLRulesetService;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.openapi.*;

/**
 * Routes for NCL ruleset operations — delegates to NCLRulesetService.
 */
public class NCLRulesetRoutes {
    private static NCLRulesetService service;

    public static void register(Javalin app, NCLRulesetService svc) {
        service = svc;

        app.get("/api/nclrulesets/names", NCLRulesetRoutes::listNames);
    }

    @OpenApi(path = "/api/nclrulesets/names", methods = HttpMethod.GET, summary = "List NCL Ruleset Names", tags = {
            "NCL Rulesets" })
    private static void listNames(Context ctx) {
        ctx.json(service.listNames());
    }
}
