package com.tcn.sati.core.service.dto;

import io.javalin.openapi.OpenApiByFields;

/**
 * DTOs for ScrubListService responses.
 */
public class ScrubListDto {

    /** Scrub list entry — returned by list. */
    @OpenApiByFields
    public static class ScrubListEntry {
        public String scrubListId;
        public boolean readOnly;
        public String contentType;
    }

    // ========== Request DTOs ==========

    /** Request for upserting a scrub list entry. */
    @OpenApiByFields
    public static class UpsertScrubEntryRequest {
        public String scrubListId;
        public String content;
        public String expirationDate;
        public String notes;
        public String countryCode;
    }
}
