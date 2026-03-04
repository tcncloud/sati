package com.example.app;

import com.tcn.sati.core.service.AgentService;
import com.tcn.sati.core.service.dto.AgentDto.AgentInfo;
import com.tcn.sati.core.service.dto.AgentDto.DialRequest;
import com.tcn.sati.core.service.dto.AgentDto.DialResult;
import com.tcn.sati.core.service.dto.AgentDto.ListAgentsRequest;
import com.tcn.sati.core.service.dto.AgentDto.UpsertAgentRequest;
import com.tcn.sati.core.service.dto.SuccessResult;
import com.tcn.sati.infra.gate.GateClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Set;

// Example of overriding a built-in Sati service.
//
// Register via the builder:
//   SatiApp.builder()
//       .agentService(CustomAgentService::new)   // factory: GateClient -> AgentService
//
// The routes call service methods polymorphically, so the same HTTP endpoints
// and Swagger docs work — your overrides just run underneath. Jackson auto-
// serializes any extra subclass fields into the JSON response.
public class CustomAgentService extends AgentService {
    private static final Logger log = LoggerFactory.getLogger(CustomAgentService.class);

    public CustomAgentService(GateClient gate) {
        super(gate);
    }

    // ================================================================
    // Overriding Responses — extend the response DTO with custom fields
    //
    // Subclass the response DTO and add your own fields. Jackson will
    // serialize them into the JSON automatically. No route changes needed.
    //
    // curl http://localhost:8080/api/agents
    // -> response includes "demoFlag": true, "queryTimeMs": 42
    // ================================================================

    // Custom response DTO — adds fields to the JSON response
    // public static class CustomAgentInfo extends AgentInfo {
    //     public boolean demoFlag;
    //     public long queryTimeMs;
    // }

    // @Override
    // public List<AgentInfo> listAgents(ListAgentsRequest request) {
    //     log.info("CustomAgentService: intercepting listAgents");
    //     long startTime = System.currentTimeMillis();

    //     List<AgentInfo> baseAgents = super.listAgents(request);
    //     long duration = System.currentTimeMillis() - startTime;

    //     log.info("CustomAgentService: retrieved {} agents in {}ms", baseAgents.size(), duration);

    //     // Convert each response to our extended version with extra fields
    //     return baseAgents.stream().map(agent -> {
    //         var custom = new CustomAgentInfo();
    //         custom.userId = agent.userId;
    //         custom.orgId = agent.orgId;
    //         custom.partnerAgentId = agent.partnerAgentId;
    //         custom.username = agent.username;
    //         custom.firstName = agent.firstName;
    //         custom.lastName = agent.lastName;
    //         custom.currentSessionId = agent.currentSessionId;
    //         custom.agentState = agent.agentState;
    //         custom.isLoggedIn = agent.isLoggedIn;
    //         // Custom response fields
    //         custom.demoFlag = true;
    //         custom.queryTimeMs = duration;
    //         return (AgentInfo) custom;
    //     }).toList();
    // }


    // ================================================================
    // Restrict behavior — enforce business rules on state transitions
    //
    // curl -X PUT http://localhost:8080/api/agents/agent-123/state/LUNCH
    // -> throws IllegalArgumentException ("State LUNCH not allowed")
    // ================================================================

    // private static final Set<String> ALLOWED_STATES = Set.of(
    //         "READY", "PAUSED", "WRAP_UP", "LOGGED_OUT");

    // @Override
    // public SuccessResult updateAgentState(String agentId, String state, String reason) {
    //     String normalized = state.toUpperCase().replace("AGENT_STATE_", "");

    //     if (!ALLOWED_STATES.contains(normalized)) {
    //         throw new IllegalArgumentException(
    //                 "State " + normalized + " not allowed. Allowed: " + ALLOWED_STATES);
    //     }

    //     log.info("CustomAgentService: {} -> state {} (reason: {})", agentId, normalized, reason);
    //     return super.updateAgentState(agentId, state, reason);
    // }
}
