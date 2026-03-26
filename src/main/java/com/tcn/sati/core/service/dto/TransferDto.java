package com.tcn.sati.core.service.dto;

import io.javalin.openapi.OpenApiByFields;

/**
 * DTOs for TransferService requests and responses.
 */
public class TransferDto {

    // ========== Request DTOs ==========

    /** Request for executing a call transfer. */
    @OpenApiByFields
    public static class TransferRequest {
        public String partner_agent_id;
        public Agent receiving_partner_agent_id;
        public Outbound outbound;
        public Queue queue;
        public String kind; // COLD, WARM, CONFERENCE
        public String action; // START, APPROVE, CANCEL
    }

    @OpenApiByFields
    public static class Agent {
        public String partner_agent_id;
    }

    @OpenApiByFields
    public static class Outbound {
        public String caller_id;
        public String destination;
        public Boolean caller_hold;
    }

    @OpenApiByFields
    public static class Queue {
    }

    // ========== Response DTOs ==========

    /** Response from a transfer operation. */
    @OpenApiByFields
    public static class TransferResponse {
    }
}
