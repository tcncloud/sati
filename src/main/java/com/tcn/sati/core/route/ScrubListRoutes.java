package com.tcn.sati.core.route;

import com.tcn.sati.core.service.ScrubListService;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.openapi.*;

/**
 * Routes for DNC scrub list management — delegates to ScrubListService.
 */
public class ScrubListRoutes {
    private static ScrubListService service;

    public static void register(Javalin app, ScrubListService svc) {
        service = svc;

        app.get("/api/scrublists", ScrubListRoutes::list);
        app.post("/api/scrublists/{scrubListId}", ScrubListRoutes::upsertEntry);
        app.delete("/api/scrublists/{scrubListId}/delete/{content}", ScrubListRoutes::deleteEntry);
    }

    public record ScrubListEntry(String content, String expirationDate, String notes, String countryCode) {
    }

    @OpenApi(path = "/api/scrublists", methods = HttpMethod.GET, summary = "List Scrub Lists", tags = { "Scrub Lists" })
    private static void list(Context ctx) {
        ctx.json(service.list());
    }

    @OpenApi(path = "/api/scrublists/{scrubListId}", methods = HttpMethod.POST, summary = "Upsert Scrub List Entry", tags = {
            "Scrub Lists" }, requestBody = @OpenApiRequestBody(content = @OpenApiContent(from = ScrubListEntry.class)))
    private static void upsertEntry(Context ctx) {
        var body = ctx.bodyAsClass(ScrubListEntry.class);
        ctx.json(service.upsertEntry(ctx.pathParam("scrubListId"),
                body.content(), body.expirationDate(), body.notes(), body.countryCode()));
    }

    @OpenApi(path = "/api/scrublists/{scrubListId}/delete/{content}", methods = HttpMethod.DELETE, summary = "Delete Scrub List Entry", tags = {
            "Scrub Lists" })
    private static void deleteEntry(Context ctx) {
        ctx.json(service.deleteEntry(ctx.pathParam("scrubListId"), ctx.pathParam("content")));
    }
}
