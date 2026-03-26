package com.tcn.sati.core.service.dto;

import io.javalin.openapi.OpenApiByFields;

/**
 * DTOs for ScrubListService responses.
 */
public class ScrubListDto {

    /** Scrub list entry — returned by list. */
    @OpenApiByFields
    public static class ScrubListEntry {
        public String scrub_list_id;
        public boolean read_only;
        public String content_type;
    }

    // ========== Request DTOs ==========

    /** Request for upserting a scrub list entry. */
    @OpenApiByFields
    public static class UpsertScrubEntryRequest {
        public String content;
        public String expiration_date;
        public String notes;
        public String country_code;
    }
}
