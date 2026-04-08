package com.tcn.exile.model.event;

import com.tcn.exile.model.CallType;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

public record TransferInstanceEvent(
    long clientSid,
    String orgId,
    long transferInstanceId,
    Source source,
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
    Duration fullDuration) {

  public record Source(
      long callSid,
      CallType callType,
      String partnerAgentId,
      String userId,
      String conversationId,
      long sessionSid,
      long agentCallSid) {}
}
