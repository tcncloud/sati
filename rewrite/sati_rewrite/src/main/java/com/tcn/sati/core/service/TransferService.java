package com.tcn.sati.core.service;

/**
 * Transfer service - handles call transfer operations.
 * Can be extended by tenants to add custom pre/post logic.
 */
public class TransferService {

    // Simple DTO
    public record TransferRequest(String agentId, String targetQueue) {
    }

    public record TransferResponse(boolean success, String message) {
    }

    // Virtual method - can be overridden
    public TransferResponse executeTransfer(TransferRequest req) {
        System.out.println("SATI (Base): Standard transfer logic for " + req.agentId());
        return new TransferResponse(true, "Standard Transfer to " + req.targetQueue());
    }
}
