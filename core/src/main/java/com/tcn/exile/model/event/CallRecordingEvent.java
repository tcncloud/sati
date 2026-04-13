package com.tcn.exile.model.event;

import com.tcn.exile.model.CallType;
import java.time.Duration;
import java.time.Instant;

public record CallRecordingEvent(
    String recordingId,
    String orgId,
    long callSid,
    CallType callType,
    Duration duration,
    String recordingType,
    Instant startTime) {}
