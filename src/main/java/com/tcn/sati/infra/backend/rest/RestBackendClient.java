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
                            node.path("status").asText()));
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
                    root.path("status").asText("UNKNOWN"));

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
                    node.fields().forEachRemaining(entry -> fields.put(entry.getKey(), entry.getValue().asText()));

                    records.add(new PoolRecord(
                            node.path("recordId").asText(),
                            poolId,
                            fields));
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
            log.info("Handling telephony result for callSid: {}", result.callSid);

            Map<String, Object> payload = new HashMap<>();
            payload.put("callSid", result.callSid);
            payload.put("status", result.status);
            payload.put("result", result.result);
            if (result.metadata != null) {
                payload.putAll(result.metadata);
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
            log.info("Handling task: {} for pool: {}", task.taskSid, task.poolId);

            Map<String, Object> payload = new HashMap<>();
            payload.put("taskSid", task.taskSid);
            payload.put("poolId", task.poolId);
            payload.put("recordId", task.recordId);
            payload.put("status", task.status);

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
            log.info("Handling agent call: {} for callSid: {}", call.agentCallSid, call.callSid);

            Map<String, Object> payload = new HashMap<>();
            payload.put("agentCallSid", call.agentCallSid);
            payload.put("callSid", call.callSid);
            payload.put("userId", call.userId);
            if (call.durations != null) {
                payload.putAll(call.durations);
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
            log.info("Handling agent response: {} key: {}", response.agentCallResponseSid, response.responseKey);

            Map<String, Object> payload = new HashMap<>();
            payload.put("agentCallResponseSid", response.agentCallResponseSid);
            payload.put("callSid", response.callSid);
            payload.put("responseKey", response.responseKey);
            payload.put("responseValue", response.responseValue);

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
    public void handleTransferInstance(TransferInstance transfer) {
        try {
            log.info("Handling transfer instance: {}", transfer.transferInstanceSid);

            Map<String, Object> payload = new HashMap<>();
            payload.put("transferInstanceSid", transfer.transferInstanceSid);
            payload.put("callSid", transfer.callSid);
            payload.put("status", transfer.status);

            var request = requestBuilder("/api/v1/transfer-instances")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
                    .build();

            var httpResponse = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (httpResponse.statusCode() >= 400) {
                log.error("Failed to post transfer instance: {} - {}", httpResponse.statusCode(), httpResponse.body());
            }

        } catch (Exception e) {
            log.error("Failed to handle transfer instance", e);
            throw new RuntimeException("API error", e);
        }
    }

    @Override
    public void handleCallRecording(CallRecording recording) {
        try {
            log.info("Handling call recording: {} for callSid: {}", recording.recordingSid, recording.callSid);

            Map<String, Object> payload = new HashMap<>();
            payload.put("recordingSid", recording.recordingSid);
            payload.put("callSid", recording.callSid);
            payload.put("status", recording.status);

            var request = requestBuilder("/api/v1/call-recordings")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
                    .build();

            var httpResponse = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (httpResponse.statusCode() >= 400) {
                log.error("Failed to post call recording: {} - {}", httpResponse.statusCode(), httpResponse.body());
            }

        } catch (Exception e) {
            log.error("Failed to handle call recording", e);
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

    /**
     * Get REST API connection stats for the admin dashboard.
     */
    public Map<String, Object> getConnectionStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("baseUrl", baseUrl);
        stats.put("authMethod", authHeader != null ? "Basic Auth" : "None");
        return stats;
    }

    // ========== Job Handlers ==========

    @Override
    public PopAccountResult popAccount(PopAccountRequest request) {
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("recordId", request.recordId);
            payload.put("userId", request.userId);
            payload.put("callSid", request.callSid);
            payload.put("callType", request.callType);

            var req = requestBuilder("/api/v1/pop-account")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
                    .build();
            var resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            return new PopAccountResult(resp.statusCode() < 400);

        } catch (Exception e) {
            log.error("Failed to pop account", e);
            throw new RuntimeException("API error", e);
        }
    }

    @Override
    public List<SearchResult> searchRecords(SearchRecordsRequest request) {
        try {
            String url = String.format("/api/v1/search?type=%s&value=%s",
                    request.lookupType, request.lookupValue);
            var req = requestBuilder(url).GET().build();
            var resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());

            List<SearchResult> results = new ArrayList<>();
            JsonNode root = objectMapper.readTree(resp.body());
            if (root.isArray()) {
                for (JsonNode node : root) {
                    Map<String, Object> fields = new HashMap<>();
                    node.fields().forEachRemaining(e -> fields.put(e.getKey(), e.getValue().asText()));
                    results.add(new SearchResult(
                            node.path("recordId").asText(), node.path("poolId").asText(""), fields));
                }
            }
            return results;

        } catch (Exception e) {
            log.error("Failed to search records", e);
            throw new RuntimeException("API error", e);
        }
    }

    @Override
    public List<RecordField> readFields(ReadFieldsRequest request) {
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("recordId", request.recordId);
            payload.put("fieldNames", request.fieldNames);

            var req = requestBuilder("/api/v1/record-fields/read")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
                    .build();
            var resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());

            List<RecordField> fields = new ArrayList<>();
            JsonNode root = objectMapper.readTree(resp.body());
            if (root.isArray()) {
                for (JsonNode node : root) {
                    fields.add(new RecordField(
                            request.recordId, request.poolId,
                            node.path("fieldName").asText(), node.path("fieldValue").asText()));
                }
            }
            return fields;

        } catch (Exception e) {
            log.error("Failed to read fields", e);
            throw new RuntimeException("API error", e);
        }
    }

    @Override
    public void writeFields(WriteFieldsRequest request) {
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("recordId", request.recordId);
            payload.put("fields", request.fields);

            var req = requestBuilder("/api/v1/record-fields/write")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
                    .build();
            httpClient.send(req, HttpResponse.BodyHandlers.ofString());

        } catch (Exception e) {
            log.error("Failed to write fields", e);
            throw new RuntimeException("API error", e);
        }
    }

    @Override
    public void createPayment(CreatePaymentRequest request) {
        try {
            var req = requestBuilder("/api/v1/payments")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(request)))
                    .build();
            httpClient.send(req, HttpResponse.BodyHandlers.ofString());

        } catch (Exception e) {
            log.error("Failed to create payment", e);
            throw new RuntimeException("API error", e);
        }
    }

    @Override
    public String executeLogic(ExecuteLogicRequest request) {
        try {
            var req = requestBuilder("/api/v1/execute-logic")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(request)))
                    .build();
            var resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            return resp.body();

        } catch (Exception e) {
            log.error("Failed to execute logic", e);
            throw new RuntimeException("API error", e);
        }
    }

    @Override
    public void close() {
        log.info("Closing RestBackendClient");
        // HttpClient doesn't require explicit closing in Java 11+
    }
}
