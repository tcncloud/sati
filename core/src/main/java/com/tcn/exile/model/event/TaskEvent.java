package com.tcn.exile.model.event;

import java.time.Instant;

public record TaskEvent(
    long taskSid,
    long taskGroupSid,
    String orgId,
    long clientSid,
    String poolId,
    String recordId,
    long attempts,
    String status,
    Instant createTime,
    Instant updateTime) {}
