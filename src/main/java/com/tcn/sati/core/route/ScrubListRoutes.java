package com.tcn.sati.core.route;

import com.tcn.sati.core.service.ScrubListService;
import com.tcn.sati.core.service.dto.ScrubListDto;
import com.tcn.sati.core.service.dto.SuccessResult;
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

    @OpenApi(path = "/api/scrublists", methods = HttpMethod.GET, summary = "List Scrub Lists", tags = {
            "Scrub Lists" }, responses = @OpenApiResponse(status = "200", content = @OpenApiContent(from = ScrubListDto.ScrubListEntry[].class)))
    private static void list(Context ctx) {
        ctx.json(service.list());
    }

    @OpenApi(path = "/api/scrublists/{scrubListId}", methods = HttpMethod.POST, summary = "Upsert Scrub List Entry", tags = {
            "Scrub Lists" }, pathParams = @OpenApiParam(name = "scrubListId", required = true), requestBody = @OpenApiRequestBody(content = @OpenApiContent(from = ScrubListDto.UpsertScrubEntryRequest.class)), responses = @OpenApiResponse(status = "200", content = @OpenApiContent(from = SuccessResult.class)))
    private static void upsertEntry(Context ctx) {
        var body = ctx.bodyAsClass(ScrubListDto.UpsertScrubEntryRequest.class);
        body.scrubListId = ctx.pathParam("scrubListId");
        ctx.json(service.upsertEntry(body));
    }

    @OpenApi(path = "/api/scrublists/{scrubListId}/delete/{content}", methods = HttpMethod.DELETE, summary = "Delete Scrub List Entry", tags = {
            "Scrub Lists" }, pathParams = {
                    @OpenApiParam(name = "scrubListId", required = true),
                    @OpenApiParam(name = "content", required = true, description = "Content to remove from the scrub list")
            }, responses = @OpenApiResponse(status = "200", content = @OpenApiContent(from = SuccessResult.class)))
    private static void deleteEntry(Context ctx) {
        ctx.json(service.deleteEntry(ctx.pathParam("scrubListId"), ctx.pathParam("content")));
    }
}
