package com.tcn.exile.model.event;

import com.tcn.exile.model.CallType;
import java.time.Duration;
import java.time.Instant;

public record TransferInstanceEvent(
    long clientSid,
    String orgId,
    String transferInstanceId,
    Source source,
    Destination destination,
    String transferType,
    String transferResult,
    String initiation,
    Instant createTime,
    Instant transferTime,
    Instant acceptTime,
    Instant hangupTime,
    Instant endTime,
    Instant updateTime,
    Duration pendingDuration,
    Duration externalDuration,
    Duration fullDuration,
    boolean startAsPending,
    boolean startedAsConference,
    long durationMicroseconds,
    long externalDurationMicroseconds,
    long pendingDurationMicroseconds,
    Instant transferPendingStartTime,
    Instant transferStartTime,
    Instant transferEndTime,
    Instant transferExternalEndTime) {

  public record Source(Call call) {
    public record Call(
        long callSid,
        CallType callType,
        String partnerAgentId,
        String userId,
        String conversationId,
        long sessionSid,
        long agentCallSid) {}
  }

  public record Destination(
      Agent agent,
      Outbound outbound,
      Skills skills) {
    public record Agent(long sessionSid, String partnerAgentId, String userId) {}
    public record Outbound(String phoneNumber, long callSid, CallType callType, String conversationId) {}
    public record Skills(java.util.Map<String, Long> requiredSkills) {}
  }
}
