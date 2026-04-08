package com.tcn.exile.model.event;

import com.tcn.exile.model.CallType;
import com.tcn.exile.model.TaskData;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

public record TelephonyResultEvent(
    long callSid,
    CallType callType,
    String orgId,
    String internalKey,
    String callerId,
    String phoneNumber,
    String poolId,
    String recordId,
    long clientSid,
    String status,
    String outcomeCategory,
    String outcomeDetail,
    Duration deliveryLength,
    Duration linkbackLength,
    Instant createTime,
    Instant updateTime,
    Instant startTime,
    Instant endTime,
    List<TaskData> taskData) {}
