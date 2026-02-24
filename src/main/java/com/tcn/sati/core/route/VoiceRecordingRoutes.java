package com.tcn.sati.core.route;

import com.tcn.sati.core.service.VoiceRecordingService;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.openapi.*;

import java.util.List;
import java.util.Map;

/**
 * Routes for voice recording operations — delegates to VoiceRecordingService.
 */
public class VoiceRecordingRoutes {
    private static VoiceRecordingService service;

    public static void register(Javalin app, VoiceRecordingService svc) {
        service = svc;

        app.get("/api/voice-recordings", VoiceRecordingRoutes::search);
        app.get("/api/voice-recordings/download-link", VoiceRecordingRoutes::downloadLink);
        app.get("/api/voice-recordings/list-search-options", VoiceRecordingRoutes::listSearchOptions);
        app.post("/api/voice-recordings/label-recording", VoiceRecordingRoutes::labelRecording);
    }

    public record LabelRecordingRequest(long callSid, String callType, String key, String value) {
    }

    @OpenApi(path = "/api/voice-recordings", methods = HttpMethod.GET, summary = "Search Voice Recordings", tags = {
            "Voice Recordings" }, queryParams = {
                    @OpenApiParam(name = "searchOption", description = "Format: field,operator,value (e.g. client_phone.region_name,CONTAINS,California)", required = true)
            })
    private static void search(Context ctx) {
        List<String> searchOptions = ctx.queryParams("searchOption");
        if (searchOptions == null || searchOptions.isEmpty()) {
            ctx.status(400).json(Map.of("error", "searchOption query param required"));
            return;
        }
        ctx.json(service.search(searchOptions));
    }

    @OpenApi(path = "/api/voice-recordings/download-link", methods = HttpMethod.GET, summary = "Get Voice Recording Download Link", tags = {
            "Voice Recordings" }, queryParams = {
                    @OpenApiParam(name = "recordingId", required = true),
                    @OpenApiParam(name = "startOffset", required = false),
                    @OpenApiParam(name = "endOffset", required = false)
            })
    private static void downloadLink(Context ctx) {
        String recordingId = ctx.queryParam("recordingId");
        if (recordingId == null || recordingId.isBlank()) {
            ctx.status(400).json(Map.of("error", "recordingId required"));
            return;
        }
        ctx.json(service.getDownloadLink(recordingId,
                ctx.queryParam("startOffset"), ctx.queryParam("endOffset")));
    }

    @OpenApi(path = "/api/voice-recordings/list-search-options", methods = HttpMethod.GET, summary = "List Searchable Recording Fields", tags = {
            "Voice Recordings" })
    private static void listSearchOptions(Context ctx) {
        ctx.json(service.listSearchableFields());
    }

    @OpenApi(path = "/api/voice-recordings/label-recording", methods = HttpMethod.POST, summary = "Create Recording Label", tags = {
            "Voice Recordings" }, requestBody = @OpenApiRequestBody(content = @OpenApiContent(from = LabelRecordingRequest.class)))
    private static void labelRecording(Context ctx) {
        var body = ctx.bodyAsClass(LabelRecordingRequest.class);
        ctx.json(service.createLabel(body.callSid(), body.callType(), body.key(), body.value()));
    }
}
