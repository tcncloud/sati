package com.tcn.exile.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.tcn.exile.service.TranscriptService;
import java.util.Comparator;
import java.util.List;

/** summaryStatus is null when the call has no transcript. */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record CallTranscriptSummaryDto(
    String transcript, List<String> summaryBulletPoints, String summaryStatus) {

  public static CallTranscriptSummaryDto from(TranscriptService.CallTranscriptSummary summary) {
    // Threads are per speaker; sorting the words by offset yields the
    // conversation in chronological order.
    var words =
        summary.threads().stream()
            .flatMap(t -> t.segments().stream())
            .sorted(Comparator.comparing(TranscriptService.Segment::offset))
            .toList();
    return new CallTranscriptSummaryDto(
        joinWords(words),
        summary.summaryBulletPoints(),
        summary.summaryStatus() != null ? summary.summaryStatus().name() : null);
  }

  // Segments arrive one ASR word each. Continuation fragments (leading
  // hyphen/apostrophe, e.g. "-ahead") attach to the previous word.
  private static String joinWords(List<TranscriptService.Segment> segments) {
    var sb = new StringBuilder();
    for (var segment : segments) {
      var word = segment.text();
      if (word == null || word.isEmpty()) {
        continue;
      }
      if (sb.isEmpty() || word.charAt(0) == '-' || word.charAt(0) == '\'') {
        sb.append(word);
      } else {
        sb.append(' ').append(word);
      }
    }
    return sb.toString();
  }
}
