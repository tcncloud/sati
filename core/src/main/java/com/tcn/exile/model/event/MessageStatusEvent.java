package com.tcn.exile.model.event;

import java.time.Instant;
import java.util.Map;

public record MessageStatusEvent(
    String messageRef,
    String poolId,
    String recordId,
    String channel,
    String status,
    String statusMessage,
    Instant updateTime,
    String orgId,
    Map<String, String> metadata) {}
