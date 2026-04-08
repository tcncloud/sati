package com.tcn.exile.service;

import io.grpc.ManagedChannel;
import java.time.Instant;
import java.util.List;
import tcnapi.exile.scrublist.v3.*;

/** Scrub list management. No proto types in the public API. */
public final class ScrubListService {

  private final ScrubListServiceGrpc.ScrubListServiceBlockingStub stub;

  ScrubListService(ManagedChannel channel) {
    this.stub = ScrubListServiceGrpc.newBlockingStub(channel);
  }

  public record ScrubList(String scrubListId, boolean readOnly, String contentType) {}

  public record ScrubListEntry(
      String content, Instant expiration, String notes, String countryCode) {}

  public List<ScrubList> listScrubLists() {
    return stub.listScrubLists(ListScrubListsRequest.getDefaultInstance()).getScrubListsList()
        .stream()
        .map(sl -> new ScrubList(sl.getScrubListId(), sl.getReadOnly(),
            sl.getContentType().name()))
        .toList();
  }

  public void addEntries(String scrubListId, List<ScrubListEntry> entries,
      String defaultCountryCode) {
    var req = AddEntriesRequest.newBuilder().setScrubListId(scrubListId);
    if (defaultCountryCode != null) req.setDefaultCountryCode(defaultCountryCode);
    for (var e : entries) {
      var eb = tcnapi.exile.types.v3.ScrubListEntry.newBuilder().setContent(e.content());
      if (e.expiration() != null) {
        eb.setExpiration(com.google.protobuf.Timestamp.newBuilder()
            .setSeconds(e.expiration().getEpochSecond()));
      }
      if (e.notes() != null) eb.setNotes(e.notes());
      if (e.countryCode() != null) eb.setCountryCode(e.countryCode());
      req.addEntries(eb);
    }
    stub.addEntries(req.build());
  }

  public void updateEntry(String scrubListId, ScrubListEntry entry) {
    var eb = tcnapi.exile.types.v3.ScrubListEntry.newBuilder().setContent(entry.content());
    if (entry.expiration() != null) {
      eb.setExpiration(com.google.protobuf.Timestamp.newBuilder()
          .setSeconds(entry.expiration().getEpochSecond()));
    }
    if (entry.notes() != null) eb.setNotes(entry.notes());
    if (entry.countryCode() != null) eb.setCountryCode(entry.countryCode());
    stub.updateEntry(
        UpdateEntryRequest.newBuilder().setScrubListId(scrubListId).setEntry(eb).build());
  }

  public void removeEntries(String scrubListId, List<String> entries) {
    stub.removeEntries(
        RemoveEntriesRequest.newBuilder()
            .setScrubListId(scrubListId)
            .addAllEntries(entries)
            .build());
  }
}
