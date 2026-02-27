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
        public String partnerAgentId;
        public String kind; // COLD or WARM
        public String action; // START, CANCEL, COMPLETE
        public String receivingPartnerAgentId;
        public String outboundDestination;
        public String outboundCallerId;
        public Boolean outboundCallerHold;
        public boolean queue;
    }

    // ========== Response DTOs ==========

    /** Response from a transfer operation. */
    @OpenApiByFields
    public static class TransferResponse {
        public boolean success;
        public String message;

        public TransferResponse() {
        }

        public TransferResponse(boolean success, String message) {
            this.success = success;
            this.message = message;
        }
    }
}
