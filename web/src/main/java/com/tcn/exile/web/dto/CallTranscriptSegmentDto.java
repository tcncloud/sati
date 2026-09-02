package com.tcn.exile.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.tcn.exile.service.TranscriptService;
import java.time.Duration;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record CallTranscriptSegmentDto(
    String label, String value, String startOffset, String endOffset, String transcript) {

  public static CallTranscriptSegmentDto from(TranscriptService.CallTranscriptSegment segment) {
    return new CallTranscriptSegmentDto(
        segment.label(),
        segment.value(),
        formatDuration(segment.startOffset()),
        formatDuration(segment.endOffset()),
        segment.conversation());
  }

  private static String formatDuration(Duration d) {
    if (d == null) return null;
    return String.format("%d.%03d", d.getSeconds(), d.toMillisPart());
  }
}
