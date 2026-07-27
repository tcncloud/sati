package com.tcn.exile.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.tcn.exile.service.TranscriptService;
import java.util.List;
import java.util.stream.IntStream;

/** summaryStatus is null when the call has no transcript. */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record CallTranscriptSummaryDto(
    String transcript,
    List<TranscriptThreadDto> threads,
    List<String> summaryBulletPoints,
    String summaryStatus) {

  public static CallTranscriptSummaryDto from(TranscriptService.CallTranscriptSummary summary) {
    var speakers = summary.speakers();
    var threads =
        IntStream.range(0, summary.threads().size())
            .mapToObj(
                i -> {
                  var thread = summary.threads().get(i);
                  return new TranscriptThreadDto(
                      thread.id(), speakers.get(i), thread.userId(), thread.text());
                })
            .toList();
    return new CallTranscriptSummaryDto(
        summary.conversation(),
        threads,
        summary.summaryBulletPoints(),
        summary.summaryStatus() != null ? summary.summaryStatus().name() : null);
  }
}
