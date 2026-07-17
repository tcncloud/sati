package com.tcn.exile.service;

import com.tcn.exile.internal.ProtoConverter;
import com.tcn.exile.model.CallType;
import io.grpc.ManagedChannel;
import java.time.Duration;
import java.util.List;

/** Call transcript and AI summary retrieval. No proto types in the public API. */
public final class TranscriptService {

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

  public record TranscriptThread(int id, String userId, List<Segment> segments) {}

  public record CallTranscriptSummary(
      boolean transcriptFound,
      List<TranscriptThread> threads,
      List<String> summaryBulletPoints,
      SummaryStatus summaryStatus) {}

  public CallTranscriptSummary getCallTranscriptSummary(long callSid, CallType callType) {
    var resp =
        stub.getCallTranscriptSummary(
            build.buf.gen.tcnapi.exile.gate.v3.GetCallTranscriptSummaryRequest.newBuilder()
                .setCallSid(callSid)
                .setCallType(
                    build.buf.gen.tcnapi.exile.gate.v3.CallType.valueOf(
                        "CALL_TYPE_" + callType.name()))
                .build());

    var threads =
        resp.getThreadsList().stream()
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

    return new CallTranscriptSummary(
        resp.getTranscriptFound(),
        threads,
        resp.getSummaryBulletPointsList(),
        toSummaryStatus(resp));
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
