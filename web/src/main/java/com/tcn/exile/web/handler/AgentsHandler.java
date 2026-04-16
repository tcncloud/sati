package com.tcn.exile.web.handler;

import com.tcn.exile.ExileClient;
import com.tcn.exile.config.ExileClientManager;
import com.tcn.exile.model.Agent;
import com.tcn.exile.model.AgentState;
import com.tcn.exile.model.CallType;
import com.tcn.exile.service.CallService;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * Pure Java handler for agent management endpoints. No framework dependencies.
 *
 * <p>Methods that produce identical JSON across plugins return concrete types. Methods whose
 * response DTO shape varies between plugins (listAgents, createAgent, getState) return the sati
 * core {@link Agent} model so each plugin's controller can map to its own DTO.
 */
public class AgentsHandler {

  private static final Logger log = LoggerFactory.getLogger(AgentsHandler.class);

  private final ExileClientManager clientManager;

  public AgentsHandler(ExileClientManager clientManager) {
    this.clientManager = clientManager;
  }

  private ExileClient getClient() {
    var client = clientManager.client();
    if (client == null) {
      throw new IllegalStateException("ExileClient is not connected");
    }
    return client;
  }

  // ==================== Agent listing & creation ====================

  /**
   * List agents, applying optional filters. Returns the core model list; the controller shell maps
   * each Agent to its own response DTO.
   *
   * @param stateParam raw state string from query param (may be null/empty)
   * @return null when stateParam is invalid — the caller should check {@link
   *     #validateStateParam(String)} first and return 400
   */
  public List<Agent> listAgents(
      Boolean loggedIn, String stateParam, boolean includeRecordingStatus) {
    log.debug("listAgents with logged_in={}, state={}", loggedIn, stateParam);

    AgentState stateFilter = null;
    if (stateParam != null && !stateParam.isEmpty()) {
      stateFilter = parseState(stateParam);
    }

    var page =
        getClient().agents().listAgents(loggedIn, stateFilter, includeRecordingStatus, null, 0);
    return page.items();
  }

  /**
   * Validates a state parameter. Returns null if valid, or an error message if invalid. Useful for
   * controllers that need to return framework-specific 400 responses.
   */
  public String validateStateParam(String stateParam) {
    if (stateParam == null || stateParam.isEmpty()) return null;
    try {
      parseState(stateParam);
      return null;
    } catch (IllegalArgumentException e) {
      return e.getMessage();
    }
  }

  /** Returns the list of valid state names for error messages. */
  public List<String> getValidStateNames() {
    return Arrays.stream(AgentState.values()).map(AgentState::name).collect(Collectors.toList());
  }

  /**
   * Create or update an agent. Returns the core model; the controller shell maps to its own
   * response DTO.
   */
  public Agent createAgent(
      String partnerAgentId, String username, String firstName, String lastName, String password) {
    log.debug("createAgent");

    if (username == null || username.isBlank()) {
      throw new IllegalArgumentException("username is required to create or update an agent");
    }

    var result =
        getClient()
            .agents()
            .upsertAgent(
                partnerAgentId,
                username,
                firstName != null ? firstName : "",
                lastName != null ? lastName : "");

    if (result != null) {
      if (password != null) {
        getClient().agents().setAgentCredentials(result.partnerAgentId(), password);
      }
      return result;
    }
    throw new IllegalStateException("Failed to create agent: server returned an empty response");
  }

  // ==================== Dial ====================

  public CallService.DialResult dial(
      String partnerAgentId,
      String phoneNumber,
      String callerId,
      String poolId,
      String recordId,
      String rulesetName,
      Boolean skipComplianceChecks,
      Boolean recordCall) {
    log.debug("dial partnerAgentId={}, phoneNumber={}", partnerAgentId, phoneNumber);

    if (partnerAgentId == null || partnerAgentId.isBlank()) {
      throw new IllegalArgumentException("partnerAgentId is required");
    }
    if (phoneNumber == null || phoneNumber.isBlank()) {
      throw new IllegalArgumentException("phoneNumber is required to initiate a dial");
    }

    var result =
        getClient()
            .calls()
            .dial(
                partnerAgentId,
                phoneNumber,
                callerId,
                poolId,
                recordId,
                rulesetName,
                skipComplianceChecks,
                recordCall);

    if (result != null) {
      return result;
    }
    throw new IllegalStateException("Failed to dial: server returned an empty response");
  }

  // ==================== Recording ====================

  public boolean getRecordingStatus(String partnerAgentId) {
    long requestStartTime = System.currentTimeMillis();
    String requestId = MDC.get("requestId");
    String requestIdSuffix = requestId != null ? " [requestId: " + requestId + "]" : "";
    log.debug(
        "[API-TIMING] getRecording REQUEST RECEIVED for partnerAgentId: {} at {}ms{}",
        partnerAgentId,
        requestStartTime,
        requestIdSuffix);

    try {
      boolean isRecording = getClient().calls().getRecordingStatus(partnerAgentId);

      long responseTime = System.currentTimeMillis();
      long totalDuration = responseTime - requestStartTime;
      log.debug(
          "[API-TIMING] getRecording RESPONSE SENT for partnerAgentId: {} at {}ms (TOTAL"
              + " DURATION: {}ms / {}s){}",
          partnerAgentId,
          responseTime,
          totalDuration,
          String.format("%.2f", totalDuration / 1000.0),
          requestIdSuffix);

      if (totalDuration > 3000) {
        log.warn(
            "[API-TIMING] getRecording WARNING: Response took {}ms (>3s client timeout) for"
                + " partnerAgentId: {}{}",
            totalDuration,
            partnerAgentId,
            requestIdSuffix);
      }

      return isRecording;
    } catch (Exception e) {
      long errorTime = System.currentTimeMillis();
      long totalDuration = errorTime - requestStartTime;
      log.error(
          "[API-TIMING] getRecording ERROR for partnerAgentId: {} at {}ms (TOTAL DURATION: {}ms)"
              + " - {}{}",
          partnerAgentId,
          errorTime,
          totalDuration,
          e.getMessage(),
          requestIdSuffix,
          e);
      throw e;
    }
  }

  /**
   * Start or stop call recording based on a status string.
   *
   * @return true if recording was started, false if stopped
   */
  public boolean setRecordingStatus(String partnerAgentId, String status) {
    long requestStartTime = System.currentTimeMillis();
    String requestId = MDC.get("requestId");
    String requestIdSuffix = requestId != null ? " [requestId: " + requestId + "]" : "";
    log.debug(
        "[API-TIMING] setRecording REQUEST RECEIVED for partnerAgentId: {} to status: {} at"
            + " {}ms{}",
        partnerAgentId,
        status,
        requestStartTime,
        requestIdSuffix);

    try {
      boolean res = false;

      if (status.equalsIgnoreCase("on")
          || status.equalsIgnoreCase("resume")
          || status.equalsIgnoreCase("start")
          || status.equalsIgnoreCase("true")) {
        getClient().calls().startCallRecording(partnerAgentId);
        res = true;
      } else if (status.equalsIgnoreCase("off")
          || status.equalsIgnoreCase("stop")
          || status.equalsIgnoreCase("pause")
          || status.equalsIgnoreCase("paused")
          || status.equalsIgnoreCase("false")) {
        getClient().calls().stopCallRecording(partnerAgentId);
        res = false;
      } else {
        throw new IllegalArgumentException(
            String.format(
                "Invalid recording status '%s'. Valid values are: on, off, start, stop, resume,"
                    + " pause, true, false",
                status));
      }

      long responseTime = System.currentTimeMillis();
      long totalDuration = responseTime - requestStartTime;
      log.debug(
          "[API-TIMING] setRecording RESPONSE SENT for partnerAgentId: {} at {}ms (TOTAL"
              + " DURATION: {}ms / {}s){}",
          partnerAgentId,
          responseTime,
          totalDuration,
          String.format("%.2f", totalDuration / 1000.0),
          requestIdSuffix);

      if (totalDuration > 3000) {
        log.warn(
            "[API-TIMING] setRecording WARNING: Response took {}ms (>3s client timeout) for"
                + " partnerAgentId: {}{}",
            totalDuration,
            partnerAgentId,
            requestIdSuffix);
      }

      return res;
    } catch (Exception e) {
      long errorTime = System.currentTimeMillis();
      long totalDuration = errorTime - requestStartTime;
      log.error(
          "[API-TIMING] setRecording ERROR for partnerAgentId: {} at {}ms (TOTAL DURATION: {}ms)"
              + " - {}{}",
          partnerAgentId,
          errorTime,
          totalDuration,
          e.getMessage(),
          requestIdSuffix,
          e);
      throw e;
    }
  }

  // ==================== Agent state ====================

  /**
   * Get an agent by partner ID. Returns the core model; the controller shell maps to its own
   * response DTO.
   */
  public Agent getAgentState(String partnerAgentId) {
    long requestStartTime = System.currentTimeMillis();
    String requestId = MDC.get("requestId");
    String requestIdSuffix = requestId != null ? " [requestId: " + requestId + "]" : "";
    log.debug(
        "[API-TIMING] getState REQUEST RECEIVED for partnerAgentId: {} at {}ms{}",
        partnerAgentId,
        requestStartTime,
        requestIdSuffix);

    try {
      Agent agent;
      try {
        agent = getClient().agents().getAgentByPartnerId(partnerAgentId);
      } catch (RuntimeException e) {
        if (e.getMessage() != null && e.getMessage().contains("could not get agent")) {
          throw new IllegalArgumentException(
              String.format("Agent with partnerAgentId '%s' was not found", partnerAgentId));
        }
        throw e;
      }

      long responseTime = System.currentTimeMillis();
      long totalDuration = responseTime - requestStartTime;
      log.debug(
          "[API-TIMING] getState RESPONSE SENT for partnerAgentId: {} at {}ms (TOTAL DURATION:"
              + " {}ms / {}s){}",
          partnerAgentId,
          responseTime,
          totalDuration,
          String.format("%.2f", totalDuration / 1000.0),
          requestIdSuffix);

      if (totalDuration > 3000) {
        log.warn(
            "[API-TIMING] getState WARNING: Response took {}ms (>3s client timeout) for"
                + " partnerAgentId: {}{}",
            totalDuration,
            partnerAgentId,
            requestIdSuffix);
      }

      return agent;
    } catch (Exception e) {
      long errorTime = System.currentTimeMillis();
      long totalDuration = errorTime - requestStartTime;
      log.error(
          "[API-TIMING] getState ERROR for partnerAgentId: {} at {}ms (TOTAL DURATION: {}ms) -"
              + " {}{}",
          partnerAgentId,
          errorTime,
          totalDuration,
          e.getMessage(),
          requestIdSuffix,
          e);
      throw e;
    }
  }

  /**
   * Update agent state. Accepts the v3 {@link AgentState} directly — each plugin's controller maps
   * from its own request enum to this.
   */
  public void setAgentState(String partnerAgentId, AgentState state, String reason) {
    long requestStartTime = System.currentTimeMillis();
    String requestId = MDC.get("requestId");
    String requestIdSuffix = requestId != null ? " [requestId: " + requestId + "]" : "";
    log.debug(
        "[API-TIMING] setState REQUEST RECEIVED for partnerAgentId: {} to state: {} at {}ms{}",
        partnerAgentId,
        state,
        requestStartTime,
        requestIdSuffix);

    try {
      if (reason != null && reason.length() > 100) {
        log.warn(
            "Pause code reason truncated from {} to 100 characters for partnerAgentId: {}",
            reason.length(),
            partnerAgentId);
        reason = reason.substring(0, 100);
      }

      getClient().agents().updateAgentStatus(partnerAgentId, state, reason);

      long responseTime = System.currentTimeMillis();
      long totalDuration = responseTime - requestStartTime;
      log.debug(
          "[API-TIMING] setState RESPONSE SENT for partnerAgentId: {} at {}ms (TOTAL DURATION:"
              + " {}ms / {}s){}",
          partnerAgentId,
          responseTime,
          totalDuration,
          String.format("%.2f", totalDuration / 1000.0),
          requestIdSuffix);

      if (totalDuration > 3000) {
        log.warn(
            "[API-TIMING] setState WARNING: Response took {}ms (>3s client timeout) for"
                + " partnerAgentId: {}{}",
            totalDuration,
            partnerAgentId,
            requestIdSuffix);
      }
    } catch (Exception e) {
      long errorTime = System.currentTimeMillis();
      long totalDuration = errorTime - requestStartTime;
      log.error(
          "[API-TIMING] setState ERROR for partnerAgentId: {} at {}ms (TOTAL DURATION: {}ms) -"
              + " {}{}",
          partnerAgentId,
          errorTime,
          totalDuration,
          e.getMessage(),
          requestIdSuffix,
          e);
      throw e;
    }
  }

  // ==================== Pause codes ====================

  public List<String> listPauseCodes(String partnerAgentId) {
    log.debug("listPauseCodes for partnerAgentId: {}", partnerAgentId);
    if (partnerAgentId == null || partnerAgentId.isBlank()) {
      throw new IllegalArgumentException("partnerAgentId is required");
    }
    log.warn(
        "listPauseCodes not yet available in v3 Java client for partnerAgentId: {}",
        partnerAgentId);
    return List.of();
  }

  // ==================== Hold / Mute ====================

  public void hold(String partnerAgentId) {
    validatePartnerAgentId(partnerAgentId);
    getClient()
        .calls()
        .setHoldState(partnerAgentId, CallService.HoldTarget.CALL, CallService.HoldAction.HOLD);
  }

  public void unhold(String partnerAgentId) {
    validatePartnerAgentId(partnerAgentId);
    getClient()
        .calls()
        .setHoldState(partnerAgentId, CallService.HoldTarget.CALL, CallService.HoldAction.UNHOLD);
  }

  public void mute(String partnerAgentId) {
    validatePartnerAgentId(partnerAgentId);
    getClient().agents().muteAgent(partnerAgentId);
  }

  public void unmute(String partnerAgentId) {
    validatePartnerAgentId(partnerAgentId);
    getClient().agents().unmuteAgent(partnerAgentId);
  }

  // ==================== Call response ====================

  public void addAgentCallResponse(
      String partnerAgentId,
      long callSid,
      CallType callType,
      String sessionId,
      String key,
      String value) {
    validatePartnerAgentId(partnerAgentId);
    getClient()
        .agents()
        .addAgentCallResponse(partnerAgentId, callSid, callType, sessionId, key, value);
  }

  // ==================== Helpers ====================

  /**
   * Parse and validate a state parameter string. Accepts case-insensitive state names with or
   * without "AGENT_STATE_" prefix.
   */
  public AgentState parseState(String stateParam) {
    if (stateParam == null || stateParam.trim().isEmpty()) {
      throw new IllegalArgumentException("State parameter cannot be empty");
    }

    String normalized = stateParam.trim().toUpperCase();
    if (normalized.startsWith("AGENT_STATE_")) {
      normalized = normalized.substring("AGENT_STATE_".length());
    }

    try {
      return AgentState.valueOf(normalized);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(
          String.format(
              "Invalid state '%s'. Valid states are: %s",
              stateParam, String.join(", ", getValidStateNames())));
    }
  }

  /**
   * Parse a callType string from a request to the v3 CallType enum. Accepts both "CALL_TYPE_..."
   * and plain names, case-insensitive.
   */
  public static CallType parseCallType(String callTypeStr) {
    if (callTypeStr == null) return CallType.INBOUND;
    return switch (callTypeStr.toUpperCase()) {
      case "CALL_TYPE_INBOUND", "INBOUND" -> CallType.INBOUND;
      case "CALL_TYPE_OUTBOUND", "OUTBOUND" -> CallType.OUTBOUND;
      case "CALL_TYPE_PREVIEW", "PREVIEW" -> CallType.PREVIEW;
      case "CALL_TYPE_MANUAL", "MANUAL" -> CallType.MANUAL;
      case "CALL_TYPE_MAC", "MAC" -> CallType.MAC;
      default -> CallType.INBOUND;
    };
  }

  /** Parse a sessionId string to a Long, handling null/empty/"0" as null. */
  public static Long parseSessionId(String sessionId) {
    if (sessionId == null || sessionId.isEmpty() || "0".equals(sessionId)) {
      return null;
    }
    return Long.parseLong(sessionId);
  }

  private void validatePartnerAgentId(String partnerAgentId) {
    if (partnerAgentId == null || partnerAgentId.isBlank()) {
      throw new IllegalArgumentException("partnerAgentId is required");
    }
  }
}
