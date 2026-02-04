package com.tcn.sati.core.service;

/**
 * Transfer service - handles call transfer operations.
 * Can be extended by tenants to add custom pre/post logic.
 */
public class TransferService {

    /**
     * Transfer request DTO - can be extended by tenants to add custom fields.
     */
    public static class TransferRequest {
        private String agentId;
        private String targetQueue;

        public TransferRequest() {
        }

        public TransferRequest(String agentId, String targetQueue) {
            this.agentId = agentId;
            this.targetQueue = targetQueue;
        }

        public String getAgentId() {
            return agentId;
        }

        public void setAgentId(String agentId) {
            this.agentId = agentId;
        }

        public String getTargetQueue() {
            return targetQueue;
        }

        public void setTargetQueue(String targetQueue) {
            this.targetQueue = targetQueue;
        }
    }

    /**
     * Transfer response DTO - can be extended by tenants to add custom fields.
     */
    public static class TransferResponse {
        private boolean success;
        private String message;

        public TransferResponse() {
        }

        public TransferResponse(boolean success, String message) {
            this.success = success;
            this.message = message;
        }

        public boolean isSuccess() {
            return success;
        }

        public void setSuccess(boolean success) {
            this.success = success;
        }

        public String getMessage() {
            return message;
        }

        public void setMessage(String message) {
            this.message = message;
        }
    }

    // Virtual method - can be overridden
    public TransferResponse executeTransfer(TransferRequest req) {
        System.out.println("SATI (Base): Standard transfer logic for " + req.getAgentId());
        return new TransferResponse(true, "Standard Transfer to " + req.getTargetQueue());
    }
}
