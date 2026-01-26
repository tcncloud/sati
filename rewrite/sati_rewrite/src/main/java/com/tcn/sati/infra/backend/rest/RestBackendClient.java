package com.tcn.sati.infra.backend.rest;

import com.tcn.sati.config.SatiConfig;
import com.tcn.sati.infra.backend.TenantBackendClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * REST backend client - connects to external APIs via HTTP.
 * Works with any REST API that follows the tenant backend contract.
 */
public class RestBackendClient implements TenantBackendClient {
    private static final Logger log = LoggerFactory.getLogger(RestBackendClient.class);
    
    private final HttpClient httpClient;
    private final SatiConfig config;
    private final ObjectMapper objectMapper;
    private final String baseUrl;
    private final String authHeader;

    public RestBackendClient(SatiConfig config) {
        this.config = config;
        this.objectMapper = new ObjectMapper();
        
        this.baseUrl = config.backendUrl() != null ? config.backendUrl() : "";
        
        // Build basic auth header if credentials provided
        if (config.backendUser() != null && config.backendPassword() != null) {
            String credentials = config.backendUser() + ":" + config.backendPassword();
            this.authHeader = "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes());
        } else {
            this.authHeader = null;
        }
        
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
        
        log.info("RestBackendClient initialized with baseUrl: {}", baseUrl);
    }

    private HttpRequest.Builder requestBuilder(String path) {
        var builder = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + path))
            .timeout(Duration.ofSeconds(25))
            .header("Content-Type", "application/json")
            .header("X-Tenant", config.tenant() != null ? config.tenant() : "default");
        
        if (authHeader != null) {
            builder.header("Authorization", authHeader);
        }
        
        return builder;
    }

    @Override
    public List<PoolInfo> listPools() {
        try {
            var request = requestBuilder("/api/v1/pools")
                .GET()
                .build();
            
            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            
            if (response.statusCode() != 200) {
                log.error("Failed to list pools: {} - {}", response.statusCode(), response.body());
                throw new RuntimeException("API error: " + response.statusCode());
            }
            
            List<PoolInfo> pools = new ArrayList<>();
            JsonNode root = objectMapper.readTree(response.body());
            
            if (root.isArray()) {
                for (JsonNode node : root) {
                    pools.add(new PoolInfo(
                        node.path("id").asText(),
                        node.path("name").asText(),
                        node.path("status").asText()
                    ));
                }
            }
            
            return pools;
            
        } catch (Exception e) {
            log.error("Failed to list pools", e);
            throw new RuntimeException("API error", e);
        }
    }

    @Override
    public PoolStatus getPoolStatus(String poolId) {
        try {
            var request = requestBuilder("/api/v1/pools/" + poolId + "/status")
                .GET()
                .build();
            
            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            
            if (response.statusCode() != 200) {
                log.error("Failed to get pool status: {} - {}", response.statusCode(), response.body());
                return new PoolStatus(poolId, 0, 0, "ERROR");
            }
            
            JsonNode root = objectMapper.readTree(response.body());
            return new PoolStatus(
                poolId,
                root.path("totalRecords").asInt(0),
                root.path("availableRecords").asInt(0),
                root.path("status").asText("UNKNOWN")
            );
            
        } catch (Exception e) {
            log.error("Failed to get pool status for {}", poolId, e);
            throw new RuntimeException("API error", e);
        }
    }

    @Override
    public List<PoolRecord> getPoolRecords(String poolId, int page) {
        try {
            var request = requestBuilder("/api/v1/pools/" + poolId + "/records?page=" + page)
                .GET()
                .build();
            
            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            
            if (response.statusCode() != 200) {
                log.error("Failed to get pool records: {} - {}", response.statusCode(), response.body());
                throw new RuntimeException("API error: " + response.statusCode());
            }
            
            List<PoolRecord> records = new ArrayList<>();
            JsonNode root = objectMapper.readTree(response.body());
            JsonNode results = root.path("results");
            
            if (results.isArray()) {
                for (JsonNode node : results) {
                    Map<String, String> fields = new HashMap<>();
                    node.fields().forEachRemaining(entry -> 
                        fields.put(entry.getKey(), entry.getValue().asText())
                    );
                    
                    records.add(new PoolRecord(
                        node.path("recordId").asText(),
                        poolId,
                        fields
                    ));
                }
            }
            
            return records;
            
        } catch (Exception e) {
            log.error("Failed to get records for pool {}", poolId, e);
            throw new RuntimeException("API error", e);
        }
    }

    @Override
    public void handleTelephonyResult(TelephonyResult result) {
        try {
            log.info("Handling telephony result for callSid: {}", result.callSid());
            
            Map<String, Object> payload = new HashMap<>();
            payload.put("callSid", result.callSid());
            payload.put("status", result.status());
            payload.put("result", result.result());
            if (result.metadata() != null) {
                payload.putAll(result.metadata());
            }
            
            var request = requestBuilder("/api/v1/telephony-results")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
                .build();
            
            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            
            if (response.statusCode() >= 400) {
                log.error("Failed to post telephony result: {} - {}", response.statusCode(), response.body());
            }
            
        } catch (Exception e) {
            log.error("Failed to handle telephony result", e);
            throw new RuntimeException("API error", e);
        }
    }

    @Override
    public void handleTask(ExileTask task) {
        try {
            log.info("Handling task: {} for pool: {}", task.taskSid(), task.poolId());
            
            Map<String, Object> payload = new HashMap<>();
            payload.put("taskSid", task.taskSid());
            payload.put("poolId", task.poolId());
            payload.put("recordId", task.recordId());
            payload.put("status", task.status());
            
            var request = requestBuilder("/api/v1/tasks")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
                .build();
            
            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            
            if (response.statusCode() >= 400) {
                log.error("Failed to post task: {} - {}", response.statusCode(), response.body());
            }
            
        } catch (Exception e) {
            log.error("Failed to handle task", e);
            throw new RuntimeException("API error", e);
        }
    }

    @Override
    public void handleAgentCall(AgentCall call) {
        try {
            log.info("Handling agent call: {} for callSid: {}", call.agentCallSid(), call.callSid());
            
            Map<String, Object> payload = new HashMap<>();
            payload.put("agentCallSid", call.agentCallSid());
            payload.put("callSid", call.callSid());
            payload.put("userId", call.userId());
            if (call.durations() != null) {
                payload.putAll(call.durations());
            }
            
            var request = requestBuilder("/api/v1/agent-calls")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
                .build();
            
            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            
            if (response.statusCode() >= 400) {
                log.error("Failed to post agent call: {} - {}", response.statusCode(), response.body());
            }
            
        } catch (Exception e) {
            log.error("Failed to handle agent call", e);
            throw new RuntimeException("API error", e);
        }
    }

    @Override
    public void handleAgentResponse(AgentResponse response) {
        try {
            log.info("Handling agent response: {} key: {}", response.agentCallResponseSid(), response.responseKey());
            
            Map<String, Object> payload = new HashMap<>();
            payload.put("agentCallResponseSid", response.agentCallResponseSid());
            payload.put("callSid", response.callSid());
            payload.put("responseKey", response.responseKey());
            payload.put("responseValue", response.responseValue());
            
            var request = requestBuilder("/api/v1/agent-responses")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
                .build();
            
            var httpResponse = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            
            if (httpResponse.statusCode() >= 400) {
                log.error("Failed to post agent response: {} - {}", httpResponse.statusCode(), httpResponse.body());
            }
            
        } catch (Exception e) {
            log.error("Failed to handle agent response", e);
            throw new RuntimeException("API error", e);
        }
    }

    @Override
    public boolean isConnected() {
        try {
            var request = requestBuilder("/api/v1/health")
                .GET()
                .build();
            
            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200;
            
        } catch (Exception e) {
            log.warn("Connection check failed", e);
            return false;
        }
    }

    @Override
    public void close() {
        log.info("Closing RestBackendClient");
        // HttpClient doesn't require explicit closing in Java 11+
    }
}
