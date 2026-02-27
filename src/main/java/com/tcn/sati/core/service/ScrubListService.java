package com.tcn.sati.core.service;

import com.tcn.sati.core.service.dto.ScrubListDto.ScrubListEntry;
import com.tcn.sati.core.service.dto.ScrubListDto.UpsertScrubEntryRequest;
import com.tcn.sati.core.service.dto.SuccessResult;
import com.tcn.sati.infra.gate.GateClient;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Scrub list service. Subclass to override behavior.
 */
public class ScrubListService {
    protected final GateClient gate;

    public ScrubListService(GateClient gate) {
        this.gate = gate;
    }

    public List<ScrubListEntry> list() {
        var resp = gate.listScrubLists(
                build.buf.gen.tcnapi.exile.gate.v2.ListScrubListsRequest.newBuilder().build());
        List<ScrubListEntry> result = new ArrayList<>();
        for (var s : resp.getScrubListsList()) {
            var entry = new ScrubListEntry();
            entry.scrubListId = s.getScrubListId();
            entry.readOnly = s.getReadOnly();
            entry.contentType = s.getContentType().name();
            result.add(entry);
        }
        return result;
    }

    public SuccessResult upsertEntry(UpsertScrubEntryRequest request) {
        var reqBuilder = build.buf.gen.tcnapi.exile.gate.v2.UpdateScrubListEntryRequest.newBuilder()
                .setScrubListId(request.scrubListId)
                .setContent(request.content);
        if (request.expirationDate != null) {
            Instant exp = Instant.parse(request.expirationDate);
            reqBuilder.setExpiration(com.google.protobuf.Timestamp.newBuilder()
                    .setSeconds(exp.getEpochSecond()).setNanos(exp.getNano()));
        }
        if (request.notes != null)
            reqBuilder.setNotes(com.google.protobuf.StringValue.of(request.notes));
        if (request.countryCode != null)
            reqBuilder.setCountryCode(com.google.protobuf.StringValue.of(request.countryCode));

        gate.updateScrubListEntry(reqBuilder.build());
        return new SuccessResult();
    }

    public SuccessResult deleteEntry(String scrubListId, String content) {
        gate.removeScrubListEntries(
                build.buf.gen.tcnapi.exile.gate.v2.RemoveScrubListEntriesRequest.newBuilder()
                        .setScrubListId(scrubListId)
                        .addEntries(content)
                        .build());
        return new SuccessResult();
    }
}
