package com.tcn.exile.service;

import com.tcn.exile.internal.ProtoConverter;
import com.tcn.exile.model.*;
import io.grpc.ManagedChannel;
import java.time.Duration;
import java.util.List;
import java.util.stream.Collectors;
import tcnapi.exile.recording.v3.*;

/** Voice recording search and retrieval. No proto types in the public API. */
public final class RecordingService {

  private final RecordingServiceGrpc.RecordingServiceBlockingStub stub;

  RecordingService(ManagedChannel channel) {
    this.stub = RecordingServiceGrpc.newBlockingStub(channel);
  }

  public record VoiceRecording(
      String recordingId,
      long callSid,
      CallType callType,
      Duration startOffset,
      Duration endOffset,
      java.time.Instant startTime,
      Duration duration,
      String agentPhone,
      String clientPhone,
      String campaign,
      List<String> partnerAgentIds,
      String label,
      String value) {}

  public record DownloadLinks(String downloadLink, String playbackLink) {}

  public Page<VoiceRecording> searchVoiceRecordings(
      List<Filter> filters, String pageToken, int pageSize) {
    var req = SearchVoiceRecordingsRequest.newBuilder().setPageSize(pageSize);
    if (pageToken != null) req.setPageToken(pageToken);
    for (var f : filters) req.addFilters(ProtoConverter.fromFilter(f));
    var resp = stub.searchVoiceRecordings(req.build());
    var recordings =
        resp.getRecordingsList().stream()
            .map(
                r ->
                    new VoiceRecording(
                        r.getRecordingId(),
                        r.getCallSid(),
                        ProtoConverter.toCallType(r.getCallType()),
                        ProtoConverter.toDuration(r.getStartOffset()),
                        ProtoConverter.toDuration(r.getEndOffset()),
                        ProtoConverter.toInstant(r.getStartTime()),
                        ProtoConverter.toDuration(r.getDuration()),
                        r.getAgentPhone(),
                        r.getClientPhone(),
                        r.getCampaign(),
                        r.getPartnerAgentIdsList(),
                        r.getLabel(),
                        r.getValue()))
            .collect(Collectors.toList());
    return new Page<>(recordings, resp.getNextPageToken());
  }

  public DownloadLinks getDownloadLink(
      String recordingId, Duration startOffset, Duration endOffset) {
    var req = GetDownloadLinkRequest.newBuilder().setRecordingId(recordingId);
    if (startOffset != null) req.setStartOffset(ProtoConverter.fromDuration(startOffset));
    if (endOffset != null) req.setEndOffset(ProtoConverter.fromDuration(endOffset));
    var resp = stub.getDownloadLink(req.build());
    return new DownloadLinks(resp.getDownloadLink(), resp.getPlaybackLink());
  }

  public List<String> listSearchableFields() {
    return stub.listSearchableFields(ListSearchableFieldsRequest.getDefaultInstance())
        .getFieldsList();
  }

  public void createLabel(long callSid, CallType callType, String key, String value) {
    stub.createLabel(
        CreateLabelRequest.newBuilder()
            .setCallSid(callSid)
            .setCallType(tcnapi.exile.types.v3.CallType.valueOf("CALL_TYPE_" + callType.name()))
            .setKey(key)
            .setValue(value)
            .build());
  }
}
