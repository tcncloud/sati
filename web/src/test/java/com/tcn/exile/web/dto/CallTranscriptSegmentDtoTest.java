package com.tcn.exile.web.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.tcn.exile.service.TranscriptService.CallTranscriptSegment;
import com.tcn.exile.service.TranscriptService.Segment;
import com.tcn.exile.service.TranscriptService.TranscriptThread;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class CallTranscriptSegmentDtoTest {

  private static CallTranscriptSegment segment(Duration startOffset, Duration endOffset) {
    var customer =
        new TranscriptThread(
            1,
            "u1",
            List.of(
                new Segment("where", Duration.ofMillis(0), Duration.ofMillis(100)),
                new Segment("is", Duration.ofMillis(200), Duration.ofMillis(100))));
    var agent =
        new TranscriptThread(
            2, "u2", List.of(new Segment("here", Duration.ofMillis(2000), Duration.ofMillis(100))));
    return new CallTranscriptSegment(
        "accountId", "ACCT|4210", startOffset, endOffset, List.of(customer, agent));
  }

  @Test
  void keepsLabelAndValue() {
    var dto = CallTranscriptSegmentDto.from(segment(Duration.ZERO, Duration.ofSeconds(9)));

    assertEquals("accountId", dto.label());
    assertEquals("ACCT|4210", dto.value());
  }

  @Test
  void truncatesOffsetsToMilliseconds() {
    var start = Duration.ofSeconds(42).plusNanos(137482913);
    var end = Duration.ofSeconds(50).plusNanos(250913000);

    var dto = CallTranscriptSegmentDto.from(segment(start, end));

    assertEquals("42.137", dto.startOffset());
    assertEquals("50.250", dto.endOffset());
  }

  @Test
  void reportsAZeroOffset() {
    var dto = CallTranscriptSegmentDto.from(segment(Duration.ZERO, Duration.ofSeconds(9)));

    assertEquals("0.000", dto.startOffset());
  }

  @Test
  void exposesEachChannelAsAThread() {
    var dto = CallTranscriptSegmentDto.from(segment(Duration.ZERO, Duration.ofSeconds(9)));

    assertEquals(2, dto.threads().size());
    assertEquals(1, dto.threads().get(0).id());
    assertEquals("Channel 1", dto.threads().get(0).speaker());
    assertEquals("u1", dto.threads().get(0).userId());
    assertEquals("where is", dto.threads().get(0).text());
    assertEquals("Channel 2", dto.threads().get(1).speaker());
    assertEquals("here", dto.threads().get(1).text());
  }

  @Test
  void flattensThreadsIntoChannelLabeledText() {
    var dto = CallTranscriptSegmentDto.from(segment(Duration.ZERO, Duration.ofSeconds(9)));

    assertEquals("Channel 1: where is\nChannel 2: here", dto.transcript());
  }
}
