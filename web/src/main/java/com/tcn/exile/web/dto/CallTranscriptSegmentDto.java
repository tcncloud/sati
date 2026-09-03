package com.tcn.exile.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.tcn.exile.service.TranscriptService;
import java.time.Duration;
import java.util.List;
import java.util.stream.IntStream;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record CallTranscriptSegmentDto(
    String label,
    String value,
    String startOffset,
    String endOffset,
    String transcript,
    List<TranscriptThreadDto> threads) {

  public static CallTranscriptSegmentDto from(TranscriptService.CallTranscriptSegment segment) {
    var speakers = segment.speakers();
    var threads =
        IntStream.range(0, segment.threads().size())
            .mapToObj(
                i -> {
                  var thread = segment.threads().get(i);
                  return new TranscriptThreadDto(
                      thread.id(), speakers.get(i), thread.userId(), thread.text());
                })
            .toList();
    return new CallTranscriptSegmentDto(
        segment.label(),
        segment.value(),
        formatDuration(segment.startOffset()),
        formatDuration(segment.endOffset()),
        segment.conversation(),
        threads);
  }

  private static String formatDuration(Duration d) {
    if (d == null) return null;
    return String.format("%d.%03d", d.getSeconds(), d.toMillisPart());
  }
}
