package com.tcn.exile.model.event;

import java.time.Instant;
import java.util.Map;

public record TaskGroupEvent(
    String name,
    String taskGroupId,
    String taskName,
    String state,
    int statusCode,
    Instant scheduledStartTime,
    Instant scheduledStopTime,
    Instant startTime,
    Instant stopTime,
    String orgId,
    Map<String, String> metadata) {}
