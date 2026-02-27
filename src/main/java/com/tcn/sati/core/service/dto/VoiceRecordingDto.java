package com.tcn.sati.core.service.dto;

import io.javalin.openapi.OpenApiByFields;

import java.util.List;

/**
 * DTOs for VoiceRecordingService responses.
 */
public class VoiceRecordingDto {

    /** Individual recording info — returned in search results. */
    @OpenApiByFields
    public static class RecordingInfo {
        public String recordingId;
        public long callSid;
        public String callType;
        public String startTime; // ISO-8601 from proto Timestamp
        public String startOffset; // decimal seconds from proto Duration
        public String endOffset;
        public String duration;
        public String agentPhone;
        public String clientPhone;
        public String campaign;
        public List<String> partnerAgentIds;
        public String label;
        public String value;
    }

    /** Download link — returned by getDownloadLink. */
    @OpenApiByFields
    public static class DownloadLink {
        public String downloadLink;
        public String playbackLink;
    }

    // ========== Request DTOs ==========

    /** Request for searching voice recordings. */
    @OpenApiByFields
    public static class SearchRecordingsRequest {
        public List<String> searchOptions;
    }

    /** Request for getting a download link. */
    @OpenApiByFields
    public static class DownloadLinkRequest {
        public String recordingId;
        public String startOffset;
        public String endOffset;
    }

    /** Request for creating a recording label. */
    @OpenApiByFields
    public static class CreateLabelRequest {
        public long callSid;
        public String callType;
        public String key;
        public String value;
    }
}
