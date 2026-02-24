package com.example.app;

import com.tcn.sati.core.service.AgentService;
import com.tcn.sati.infra.gate.GateClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * An example of how to extend a built-in Sati service and override its
 * behavior.
 */
public class CustomAgentService extends AgentService {
    private static final Logger log = LoggerFactory.getLogger(CustomAgentService.class);

    public CustomAgentService(GateClient gate) {
        super(gate);
    }

    @Override
    public List<Map<String, Object>> listAgents(Boolean loggedIn, String state) {
        // Here we can inject custom business logic, side-effects, cache-checks, etc.
        log.info("Demo CustomAgentService: Intercepting listAgents call (loggedIn={}, state={})", loggedIn, state);

        long startTime = System.currentTimeMillis();

        // Delegate back to the default implementation to fetch data from Gate
        List<Map<String, Object>> agents = super.listAgents(loggedIn, state);

        long duration = System.currentTimeMillis() - startTime;
        log.info("Demo CustomAgentService: Successfully retrieved {} agents in {}ms", agents.size(), duration);

        // Optional: We could also mutate the `agents` list here before returning it
        // Ex: agents.forEach(agent -> agent.put("demoFlag", true));

        return agents;
    }
}
