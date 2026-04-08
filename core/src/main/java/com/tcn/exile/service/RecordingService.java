package com.tcn.exile.service;

import io.grpc.ManagedChannel;
import tcnapi.exile.recording.v3.*;

/** Thin wrapper around the v3 RecordingService gRPC stub. */
public final class RecordingService {

  private final RecordingServiceGrpc.RecordingServiceBlockingStub stub;

  public RecordingService(ManagedChannel channel) {
    this.stub = RecordingServiceGrpc.newBlockingStub(channel);
  }

  public SearchVoiceRecordingsResponse searchVoiceRecordings(
      SearchVoiceRecordingsRequest request) {
    return stub.searchVoiceRecordings(request);
  }

  public GetDownloadLinkResponse getDownloadLink(GetDownloadLinkRequest request) {
    return stub.getDownloadLink(request);
  }

  public ListSearchableFieldsResponse listSearchableFields(ListSearchableFieldsRequest request) {
    return stub.listSearchableFields(request);
  }

  public CreateLabelResponse createLabel(CreateLabelRequest request) {
    return stub.createLabel(request);
  }
}
