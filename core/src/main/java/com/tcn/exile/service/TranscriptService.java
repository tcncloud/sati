package com.tcn.exile.service;

import com.tcn.exile.internal.ProtoConverter;
import com.tcn.exile.model.CallType;
import io.grpc.ManagedChannel;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.IntStream;

/** Call transcript and AI summary retrieval. No proto types in the public API. */
public final class TranscriptService {

  // Silence long enough to end an utterance, matching the analytics platform.
  private static final Duration UTTERANCE_GAP = Duration.ofSeconds(1);

  private final build.buf.gen.tcnapi.exile.gate.v3.TranscriptServiceGrpc
          .TranscriptServiceBlockingStub
      stub;

  TranscriptService(ManagedChannel channel) {
    this.stub = build.buf.gen.tcnapi.exile.gate.v3.TranscriptServiceGrpc.newBlockingStub(channel);
  }

  /** null summaryStatus means no transcript was found. */
  public enum SummaryStatus {
    PENDING,
    AVAILABLE,
    ERRORED
  }

  public record Segment(String text, Duration offset, Duration duration) {}

  /**
   * One audio channel of a call. The id is the channel number, not a unique key -- a transferred
   * call carries one thread per agent leg, all on channel 2. userId is the agent who handled the
   * leg, which is stamped on the customer channel too, so it never identifies the speaker.
   */
  public record TranscriptThread(int id, String userId, List<Segment> segments) {

    /** This channel's words joined into text. */
    public String text() {
      return joinWords(segments);
    }
  }

  public record CallTranscriptSummary(
      boolean transcriptFound,
      List<TranscriptThread> threads,
      List<String> summaryBulletPoints,
      SummaryStatus summaryStatus) {

    /**
     * The call as channel-labeled turns, one per line. A voice carrying across channels is
     * transcribed on both, so grouping each channel's run of words into a turn keeps the duplicate
     * attributable instead of interleaving it word by word.
     */
    public String conversation() {
      return buildConversation(threads);
    }

    /** The channel label for each thread, in the same order as {@link #threads()}. */
    public List<String> speakers() {
      return channelLabels(threads);
    }
  }

  public record CallTranscriptSegment(
      String label,
      String value,
      Duration startOffset,
      Duration endOffset,
      List<TranscriptThread> threads) {

    /** The segment as channel-labeled turns, one per line. */
    public String conversation() {
      return buildConversation(threads);
    }

    /** The channel label for each thread, in the same order as {@link #threads()}. */
    public List<String> speakers() {
      return channelLabels(threads);
    }
  }

  public CallTranscriptSummary getCallTranscriptSummary(long callSid, CallType callType) {
    var resp =
        stub.getCallTranscriptSummary(
            build.buf.gen.tcnapi.exile.gate.v3.GetCallTranscriptSummaryRequest.newBuilder()
                .setCallSid(callSid)
                .setCallType(
                    build.buf.gen.tcnapi.exile.gate.v3.CallType.valueOf(
                        "CALL_TYPE_" + callType.name()))
                .build());

    return new CallTranscriptSummary(
        resp.getTranscriptFound(),
        toThreads(resp.getThreadsList()),
        resp.getSummaryBulletPointsList(),
        toSummaryStatus(resp));
  }

  public List<CallTranscriptSegment> getCallTranscriptSegments(
      long callSid,
      CallType callType,
      String label,
      String value,
      Duration startOffset,
      Duration endOffset) {
    var req =
        build.buf.gen.tcnapi.exile.gate.v3.GetCallTranscriptSegmentsRequest.newBuilder()
            .setCallSid(callSid)
            .setCallType(
                build.buf.gen.tcnapi.exile.gate.v3.CallType.valueOf("CALL_TYPE_" + callType.name()))
            .setLabel(label)
            .setValue(value);
    if (startOffset != null) req.setStartOffset(ProtoConverter.fromDuration(startOffset));
    if (endOffset != null) req.setEndOffset(ProtoConverter.fromDuration(endOffset));

    return stub.getCallTranscriptSegments(req.build()).getSegmentsList().stream()
        .map(
            s ->
                new CallTranscriptSegment(
                    s.getLabel(),
                    s.getValue(),
                    ProtoConverter.toDuration(s.getStartOffset()),
                    ProtoConverter.toDuration(s.getEndOffset()),
                    toThreads(s.getThreadsList())))
        .toList();
  }

  private static List<TranscriptThread> toThreads(
      List<build.buf.gen.tcnapi.exile.gate.v3.TranscriptThread> threads) {
    return threads.stream()
        .map(
            t ->
                new TranscriptThread(
                    t.getId(),
                    t.getUserId(),
                    t.getSegmentsList().stream()
                        .map(
                            s ->
                                new Segment(
                                    s.getText(),
                                    ProtoConverter.toDuration(s.getOffset()),
                                    ProtoConverter.toDuration(s.getDuration())))
                        .toList()))
        .toList();
  }

  private static List<String> channelLabels(List<TranscriptThread> threads) {
    return threads.stream().map(thread -> "Channel " + thread.id()).toList();
  }

  private static String buildConversation(List<TranscriptThread> threads) {
    record Spoken(int thread, Utterance utterance) {}

    var labels = channelLabels(threads);
    var spoken =
        IntStream.range(0, threads.size())
            .boxed()
            .flatMap(i -> utterances(threads.get(i).segments()).stream().map(u -> new Spoken(i, u)))
            .sorted(Comparator.comparing((Spoken s) -> s.utterance().offset()))
            .toList();

    var out = new StringBuilder();
    // Keyed on the label, not the thread: a transfer splits one channel across
    // several threads, and relabeling at each boundary reads as a new channel.
    String speaking = null;

    for (var next : spoken) {
      var label = labels.get(next.thread());
      if (!label.equals(speaking)) {
        if (!out.isEmpty()) {
          out.append('\n');
        }
        out.append(label).append(": ");
        speaking = label;
      } else {
        out.append(' ');
      }
      out.append(next.utterance().text());
    }

    return out.toString();
  }

  private record Utterance(Duration offset, String text) {}

  /**
   * Groups a channel's words into utterances. Speakers overlap constantly on a real call, so
   * interleaving raw words would alternate the labels every word or two; merging each speaker's run
   * of words first keeps the turns whole.
   */
  private static List<Utterance> utterances(List<Segment> segments) {
    var ordered =
        segments.stream()
            .filter(s -> s.text() != null && !s.text().isBlank())
            .sorted(Comparator.comparing(Segment::offset))
            .toList();
    if (ordered.isEmpty()) {
      return List.of();
    }

    var out = new ArrayList<Utterance>();
    var run = new ArrayList<Segment>();
    run.add(ordered.getFirst());

    for (var segment : ordered.subList(1, ordered.size())) {
      var previous = run.getLast();
      var spokenBy = previous.offset().plus(previous.duration()).plus(UTTERANCE_GAP);
      if (segment.offset().compareTo(spokenBy) <= 0) {
        run.add(segment);
      } else {
        out.add(new Utterance(run.getFirst().offset(), joinWords(run)));
        run = new ArrayList<>();
        run.add(segment);
      }
    }
    out.add(new Utterance(run.getFirst().offset(), joinWords(run)));

    return out;
  }

  // Segments arrive one ASR word each. Continuation fragments (leading
  // hyphen/apostrophe, e.g. "-ahead") attach to the previous word.
  private static String joinWords(List<Segment> segments) {
    var sb = new StringBuilder();
    for (var segment : segments.stream().sorted(Comparator.comparing(Segment::offset)).toList()) {
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

  private static SummaryStatus toSummaryStatus(
      build.buf.gen.tcnapi.exile.gate.v3.GetCallTranscriptSummaryResponse resp) {
    if (!resp.getTranscriptFound()) {
      return null;
    }
    return switch (resp.getSummaryStatus()) {
      case TRANSCRIPT_SUMMARY_STATUS_AVAILABLE -> SummaryStatus.AVAILABLE;
      case TRANSCRIPT_SUMMARY_STATUS_ERRORED -> SummaryStatus.ERRORED;
      default -> SummaryStatus.PENDING;
    };
  }
}
