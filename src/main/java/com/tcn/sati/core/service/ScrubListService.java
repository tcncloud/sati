package com.tcn.sati.core.service;

import com.tcn.sati.infra.gate.GateClient;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Scrub list service. Subclass to override behavior.
 */
public class ScrubListService {
    protected final GateClient gate;

    public ScrubListService(GateClient gate) {
        this.gate = gate;
    }

    public List<Map<String, Object>> list() {
        var resp = gate.listScrubLists(
                build.buf.gen.tcnapi.exile.gate.v2.ListScrubListsRequest.newBuilder().build());
        List<Map<String, Object>> result = new ArrayList<>();
        for (var s : resp.getScrubListsList()) {
            var entry = new HashMap<String, Object>();
            entry.put("scrubListId", s.getScrubListId());
            entry.put("readOnly", s.getReadOnly());
            entry.put("contentType", s.getContentType().name());
            result.add(entry);
        }
        return result;
    }

    public Map<String, Object> upsertEntry(String scrubListId, String content,
            String expirationDate, String notes, String countryCode) {
        var reqBuilder = build.buf.gen.tcnapi.exile.gate.v2.UpdateScrubListEntryRequest.newBuilder()
                .setScrubListId(scrubListId)
                .setContent(content);
        if (expirationDate != null) {
            Instant exp = Instant.parse(expirationDate);
            reqBuilder.setExpiration(com.google.protobuf.Timestamp.newBuilder()
                    .setSeconds(exp.getEpochSecond()).setNanos(exp.getNano()));
        }
        if (notes != null)
            reqBuilder.setNotes(com.google.protobuf.StringValue.of(notes));
        if (countryCode != null)
            reqBuilder.setCountryCode(com.google.protobuf.StringValue.of(countryCode));

        gate.updateScrubListEntry(reqBuilder.build());
        return Map.of("success", true);
    }

    public Map<String, Object> deleteEntry(String scrubListId, String content) {
        gate.removeScrubListEntries(
                build.buf.gen.tcnapi.exile.gate.v2.RemoveScrubListEntriesRequest.newBuilder()
                        .setScrubListId(scrubListId)
                        .addEntries(content)
                        .build());
        return Map.of("success", true);
    }
}
