package com.tcn.finvi;

import com.tcn.sati.core.service.TransferService;

/**
 * Example overriding Sati's default service.
 * 
 * Demonstrates how to override Sati's default behavior with tenant-specific
 * logic.
 * This service adds custom validation before delegating to the base
 * implementation.
 */
public class FinviTransferService extends TransferService {

    @Override
    public TransferResponse executeTransfer(TransferRequest req) {
        System.out.println("FINVI: Intercepting transfer request for agent " + req.agentId());

        // Custom PRE-validation: Block restricted queues
        if (req.targetQueue().equalsIgnoreCase("restricted")) {
            System.out.println("FINVI: Blocking transfer to restricted queue!");
            return new TransferResponse(false, "Finvi policy: transfers to 'restricted' queue are not allowed.");
        }

        // Custom PRE-validation: Check for VIP queue special handling
        if (req.targetQueue().equalsIgnoreCase("vip")) {
            System.out.println("FINVI: VIP queue detected, applying priority routing...");
            // Could add custom logic here, like logging to Finvi's database
        }

        // Delegate to base Sati implementation for standard processing
        System.out.println("FINVI: Proceeding with standard transfer logic...");
        TransferResponse baseResponse = super.executeTransfer(req);

        // Custom POST-processing: Add Finvi-specific metadata to response
        String enhancedMessage = baseResponse.message() + " [Processed by Finvi]";
        return new TransferResponse(baseResponse.success(), enhancedMessage);
    }
}
