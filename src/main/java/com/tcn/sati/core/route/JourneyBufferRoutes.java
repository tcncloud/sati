package com.tcn.sati.core.route;

import com.tcn.sati.core.service.JourneyBufferService;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.openapi.*;

import java.util.Map;

/**
 * Routes for journey buffer operations — delegates to JourneyBufferService.
 */
public class JourneyBufferRoutes {
    private static JourneyBufferService service;

    public static void register(Javalin app, JourneyBufferService svc) {
        service = svc;

        app.post("/api/journey-buffer/add-record", JourneyBufferRoutes::addRecord);
    }

    public record RecordDto(String poolId, String recordId, Map<String, Object> jsonRecordPayload) {
    }

    public record AddRecordRequest(RecordDto record) {
    }

    @OpenApi(path = "/api/journey-buffer/add-record", methods = HttpMethod.POST, summary = "Add Record to Journey Buffer", tags = {
            "Journey Buffer" }, requestBody = @OpenApiRequestBody(content = @OpenApiContent(from = AddRecordRequest.class)))
    private static void addRecord(Context ctx) {
        var body = ctx.bodyAsClass(AddRecordRequest.class);

        String jsonPayload;
        try {
            jsonPayload = new com.fasterxml.jackson.databind.ObjectMapper()
                    .writeValueAsString(body.record().jsonRecordPayload());
        } catch (Exception e) {
            ctx.status(400).json(Map.of("error", "Failed to serialize jsonRecordPayload"));
            return;
        }

        ctx.json(service.addRecord(body.record().poolId(), body.record().recordId(), jsonPayload));
    }
}
