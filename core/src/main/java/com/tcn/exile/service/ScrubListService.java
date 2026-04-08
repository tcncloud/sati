package com.tcn.exile.service;

import io.grpc.ManagedChannel;
import tcnapi.exile.scrublist.v3.*;

/** Thin wrapper around the v3 ScrubListService gRPC stub. */
public final class ScrubListService {

  private final ScrubListServiceGrpc.ScrubListServiceBlockingStub stub;

  public ScrubListService(ManagedChannel channel) {
    this.stub = ScrubListServiceGrpc.newBlockingStub(channel);
  }

  public ListScrubListsResponse listScrubLists(ListScrubListsRequest request) {
    return stub.listScrubLists(request);
  }

  public AddEntriesResponse addEntries(AddEntriesRequest request) {
    return stub.addEntries(request);
  }

  public UpdateEntryResponse updateEntry(UpdateEntryRequest request) {
    return stub.updateEntry(request);
  }

  public RemoveEntriesResponse removeEntries(RemoveEntriesRequest request) {
    return stub.removeEntries(request);
  }
}
