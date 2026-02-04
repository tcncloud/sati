package com.tcn.sati.core.service;

import java.util.Map;

/**
 * Agent service - provides agent-related operations.
 * Can be extended by tenants to add custom behavior.
 */
public class AgentService {

    public Map<String, String> getAgentStatus(String agentId) {
        System.out.println("SATI (AgentService): Standard logic for " + agentId);
        return Map.of("agentId", agentId, "status", "AVAILABLE", "source", "SatiDefault");
    }
}
