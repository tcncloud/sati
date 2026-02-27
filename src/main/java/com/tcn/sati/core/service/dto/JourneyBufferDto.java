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
        public String poolId;
        public String recordId;
        public Map<String, Object> jsonRecordPayload;
    }
}
