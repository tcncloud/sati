package com.tcn.exile.web.dto;

import com.tcn.exile.service.RecordingService;
import java.time.Duration;
import java.util.List;
import java.util.stream.Collectors;

public record VoiceRecordingDto(
    String recordingId,
    long callSid,
    String callType,
    String startTime,
    String startOffset,
    String endOffset,
    String duration,
    String agentPhone,
    String clientPhone,
    String campaign,
    List<String> partnerAgentIds,
    String label,
    String value) {

  public static VoiceRecordingDto fromV3(RecordingService.VoiceRecording recording) {
    String formattedStartTime =
        recording.startTime() != null ? recording.startTime().toString() : null;
    String formattedStartOffset = formatDuration(recording.startOffset());
    String formattedEndOffset = formatDuration(recording.endOffset());
    String formattedDuration = formatDuration(recording.duration());

    return new VoiceRecordingDto(
        recording.recordingId(),
        recording.callSid(),
        "CALL_TYPE_" + recording.callType().name(),
        formattedStartTime,
        formattedStartOffset,
        formattedEndOffset,
        formattedDuration,
        recording.agentPhone() != null && !recording.agentPhone().isEmpty()
            ? recording.agentPhone()
            : null,
        recording.clientPhone() != null && !recording.clientPhone().isEmpty()
            ? recording.clientPhone()
            : null,
        recording.campaign() != null && !recording.campaign().isEmpty()
            ? recording.campaign()
            : null,
        recording.partnerAgentIds(),
        recording.label(),
        recording.value());
  }

  public static List<VoiceRecordingDto> fromV3List(
      List<RecordingService.VoiceRecording> recordings) {
    return recordings.stream().map(VoiceRecordingDto::fromV3).collect(Collectors.toList());
  }

  private static String formatDuration(Duration d) {
    if (d == null || d.isZero()) return null;
    return String.format("%d.%03d", d.getSeconds(), d.toMillisPart());
  }
}
