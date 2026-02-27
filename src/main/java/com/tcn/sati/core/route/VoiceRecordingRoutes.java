package com.tcn.sati.core.route;

import com.tcn.sati.core.service.VoiceRecordingService;
import com.tcn.sati.core.service.dto.VoiceRecordingDto;
import com.tcn.sati.core.service.dto.SuccessResult;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.openapi.*;

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

    @OpenApi(path = "/api/voice-recordings", methods = HttpMethod.GET, summary = "Search Voice Recordings", tags = {
            "Voice Recordings" }, queryParams = @OpenApiParam(name = "searchOption", description = "Format: field,operator,value (e.g. client_phone.region_name,CONTAINS,California)", required = true), responses = @OpenApiResponse(status = "200", content = @OpenApiContent(from = VoiceRecordingDto.RecordingInfo[].class)))
    private static void search(Context ctx) {
        var searchOptions = ctx.queryParams("searchOption");
        if (searchOptions == null || searchOptions.isEmpty()) {
            ctx.status(400).json(Map.of("error", "searchOption query param required"));
            return;
        }
        var request = new VoiceRecordingDto.SearchRecordingsRequest();
        request.searchOptions = searchOptions;
        ctx.json(service.search(request));
    }

    @OpenApi(path = "/api/voice-recordings/download-link", methods = HttpMethod.GET, summary = "Get Voice Recording Download Link", tags = {
            "Voice Recordings" }, queryParams = {
                    @OpenApiParam(name = "recordingId", required = true),
                    @OpenApiParam(name = "startOffset"),
                    @OpenApiParam(name = "endOffset")
            }, responses = @OpenApiResponse(status = "200", content = @OpenApiContent(from = VoiceRecordingDto.DownloadLink.class)))
    private static void downloadLink(Context ctx) {
        String recordingId = ctx.queryParam("recordingId");
        if (recordingId == null || recordingId.isBlank()) {
            ctx.status(400).json(Map.of("error", "recordingId required"));
            return;
        }
        var request = new VoiceRecordingDto.DownloadLinkRequest();
        request.recordingId = recordingId;
        request.startOffset = ctx.queryParam("startOffset");
        request.endOffset = ctx.queryParam("endOffset");
        ctx.json(service.getDownloadLink(request));
    }

    @OpenApi(path = "/api/voice-recordings/list-search-options", methods = HttpMethod.GET, summary = "List Searchable Recording Fields", tags = {
            "Voice Recordings" }, responses = @OpenApiResponse(status = "200"))
    private static void listSearchOptions(Context ctx) {
        ctx.json(service.listSearchableFields());
    }

    @OpenApi(path = "/api/voice-recordings/label-recording", methods = HttpMethod.POST, summary = "Create Recording Label", tags = {
            "Voice Recordings" }, requestBody = @OpenApiRequestBody(content = @OpenApiContent(from = VoiceRecordingDto.CreateLabelRequest.class)), responses = @OpenApiResponse(status = "200", content = @OpenApiContent(from = SuccessResult.class)))
    private static void labelRecording(Context ctx) {
        var body = ctx.bodyAsClass(VoiceRecordingDto.CreateLabelRequest.class);
        ctx.json(service.createLabel(body));
    }
}
