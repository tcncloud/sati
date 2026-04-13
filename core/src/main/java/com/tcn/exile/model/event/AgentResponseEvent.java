package com.tcn.exile.model.event;

import com.tcn.exile.model.CallType;
import java.time.Instant;

public record AgentResponseEvent(
    long agentCallResponseSid,
    long callSid,
    CallType callType,
    String orgId,
    String userId,
    String partnerAgentId,
    String internalKey,
    long agentSid,
    long clientSid,
    String responseKey,
    String responseValue,
    Instant createTime,
    Instant updateTime) {}
