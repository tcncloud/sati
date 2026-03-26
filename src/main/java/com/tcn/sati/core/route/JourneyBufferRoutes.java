package com.tcn.sati.core.route;

import com.tcn.sati.core.service.JourneyBufferService;
import com.tcn.sati.core.service.dto.JourneyBufferDto;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.openapi.*;

/**
 * Routes for journey buffer operations — delegates to JourneyBufferService.
 */
public class JourneyBufferRoutes {
    private static JourneyBufferService service;

    public static void register(Javalin app, JourneyBufferService svc) {
        service = svc;

        app.post("/api/journey-buffer/add-record", JourneyBufferRoutes::addRecord);
    }

    @OpenApi(path = "/api/journey-buffer/add-record", methods = HttpMethod.POST, summary = "Add Record to Journey Buffer", tags = {
            "Journey Buffer" }, requestBody = @OpenApiRequestBody(content = @OpenApiContent(from = JourneyBufferDto.AddRecordRequest.class)), responses = @OpenApiResponse(status = "200", content = @OpenApiContent(from = JourneyBufferDto.AddRecordResponse.class)))
    private static void addRecord(Context ctx) {
        var body = ctx.bodyAsClass(JourneyBufferDto.AddRecordRequest.class);
        ctx.json(service.addRecord(body));
    }
}
