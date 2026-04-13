package com.tcn.exile.model.event;

import com.tcn.exile.model.CallType;
import com.tcn.exile.model.TaskData;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

public record AgentCallEvent(
    long agentCallSid,
    long callSid,
    CallType callType,
    String orgId,
    String userId,
    String partnerAgentId,
    String internalKey,
    Duration talkDuration,
    Duration waitDuration,
    Duration wrapupDuration,
    Duration pauseDuration,
    Duration transferDuration,
    Duration manualDuration,
    Duration previewDuration,
    Duration holdDuration,
    Duration agentWaitDuration,
    Duration suspendedDuration,
    Duration externalTransferDuration,
    Instant createTime,
    Instant updateTime,
    List<TaskData> taskData) {}
