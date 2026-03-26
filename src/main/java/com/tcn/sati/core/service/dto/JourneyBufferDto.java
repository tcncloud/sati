package com.tcn.sati.core.service.dto;

import io.javalin.openapi.OpenApiByFields;

import java.util.Map;

/**
 * DTOs for JourneyBufferService.
 */
public class JourneyBufferDto {

    /** Request for adding a record to the journey buffer. */
    @OpenApiByFields
    public static class AddRecordRequest {
        public RecordDto record;
    }

    @OpenApiByFields
    public static class RecordDto {
        public String poolId;
        public String recordId;
        public Map<String, Object> jsonRecordPayload;
    }

    /** Response from adding a record. */
    @OpenApiByFields
    public static class AddRecordResponse {
        public String status;

        public AddRecordResponse() {
        }

        public AddRecordResponse(String status) {
            this.status = status;
        }
    }
}
