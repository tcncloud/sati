package com.tcn.sati.core.service;

import com.tcn.sati.infra.gate.GateClient;

import build.buf.gen.tcnapi.exile.gate.v2.SearchOption;
import build.buf.gen.tcnapi.exile.gate.v2.Operator;
import build.buf.gen.tcnapi.exile.gate.v2.Recording;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Voice recording service. Subclass to override behavior.
 */
public class VoiceRecordingService {
    protected final GateClient gate;

    public VoiceRecordingService(GateClient gate) {
        this.gate = gate;
    }

    public List<Map<String, Object>> search(List<String> searchOptions) {
        var searchOptionsList = new ArrayList<SearchOption>();
        for (String option : searchOptions) {
            String[] parts = option.split(",", 3);
            if (parts.length != 3)
                continue;

            String field = parts[0].trim();
            String operatorStr = parts[1].trim().toUpperCase();
            String value = parts[2].trim();
            if ("call_type".equalsIgnoreCase(field))
                value = value.toUpperCase();

            var operator = parseOperator(operatorStr);
            searchOptionsList.add(SearchOption.newBuilder()
                    .setField(field).setOperator(operator).setValue(value).build());
        }

        var req = build.buf.gen.tcnapi.exile.gate.v2.SearchVoiceRecordingsRequest.newBuilder()
                .addAllSearchOptions(searchOptionsList).build();

        var resIterator = gate.searchVoiceRecordings(req);
        var recordings = new ArrayList<Map<String, Object>>();
        while (resIterator.hasNext()) {
            var response = resIterator.next();
            for (Recording r : response.getRecordingsList()) {
                var rec = new HashMap<String, Object>();
                rec.put("recordingId", r.getRecordingId());
                rec.put("callSid", r.getCallSid());
                rec.put("callType", r.getCallType().name()
                        .replace("CALL_TYPE_", "").toLowerCase());
                rec.put("startTime", r.hasStartTime()
                        ? java.time.Instant.ofEpochSecond(
                                r.getStartTime().getSeconds(),
                                r.getStartTime().getNanos()).toString()
                        : null);
                rec.put("startOffset", r.hasStartOffset()
                        ? formatDurationSeconds(r.getStartOffset())
                        : null);
                rec.put("endOffset", r.hasEndOffset()
                        ? formatDurationSeconds(r.getEndOffset())
                        : null);
                rec.put("duration", r.hasDuration()
                        ? formatDurationSeconds(r.getDuration())
                        : null);
                rec.put("agentPhone", r.getAgentPhone());
                rec.put("clientPhone", r.getClientPhone());
                rec.put("campaign", emptyToNull(r.getCampaign()));
                rec.put("partnerAgentIds", r.getPartnerAgentIdsList());
                rec.put("label", emptyToNull(r.getLabel()));
                rec.put("value", emptyToNull(r.getValue()));
                recordings.add(rec);
            }
        }
        return recordings;
    }

    /** Formats a protobuf Duration as decimal seconds, e.g. "53.875". */
    private static String formatDurationSeconds(com.google.protobuf.Duration d) {
        long seconds = d.getSeconds();
        int nanos = d.getNanos();
        if (nanos == 0)
            return String.valueOf(seconds);
        // Convert nanos to fractional seconds, trim trailing zeros
        String frac = String.format("%09d", nanos).replaceAll("0+$", "");
        return seconds + "." + frac;
    }

    /**
     * Returns null for empty strings so the JSON field shows as null instead of "".
     */
    private static String emptyToNull(String s) {
        return (s == null || s.isEmpty()) ? null : s;
    }

    public Map<String, Object> getDownloadLink(String recordingId, String startOffset, String endOffset) {
        var reqBuilder = build.buf.gen.tcnapi.exile.gate.v2.GetVoiceRecordingDownloadLinkRequest.newBuilder()
                .setRecordingId(recordingId);
        if (startOffset != null && !startOffset.isBlank())
            reqBuilder.setStartOffset(parseDuration(startOffset));
        if (endOffset != null && !endOffset.isBlank())
            reqBuilder.setEndOffset(parseDuration(endOffset));

        var resp = gate.getVoiceRecordingDownloadLink(reqBuilder.build());
        var result = new HashMap<String, Object>();
        result.put("downloadLink", resp.getDownloadLink());
        result.put("playbackLink", emptyToNull(resp.getPlaybackLink()));
        return result;
    }

    public Object listSearchableFields() {
        var resp = gate.listSearchableRecordingFields(
                build.buf.gen.tcnapi.exile.gate.v2.ListSearchableRecordingFieldsRequest.newBuilder().build());
        return resp.getFieldsList();
    }

    public Map<String, Object> createLabel(long callSid, String callType, String key, String value) {
        var builder = build.buf.gen.tcnapi.exile.gate.v2.CreateRecordingLabelRequest.newBuilder()
                .setCallSid(callSid)
                .setKey(key)
                .setValue(value);
        gate.createRecordingLabel(builder.build());
        return Map.of("success", true);
    }

    protected com.google.protobuf.Duration parseDuration(String durationStr) {
        String[] parts = durationStr.replace("s", "").split("\\.");
        long seconds = Long.parseLong(parts[0]);
        int nanos = parts.length > 1 ? Integer.parseInt(parts[1]) * 1_000_000 : 0;
        return com.google.protobuf.Duration.newBuilder()
                .setSeconds(seconds).setNanos(nanos).build();
    }

    protected Operator parseOperator(String input) {
        return switch (input) {
            case "EQUALS", "EQUAL", "EQ" -> Operator.EQUAL;
            case "CONTAINS", "LIKE" -> Operator.CONTAINS;
            case "NOT_EQUAL", "NOT_EQUALS", "NEQ", "NE" -> Operator.NOT_EQUAL;
            default -> {
                try {
                    yield Operator.valueOf(input);
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException("Unknown operator: " + input
                            + ". Valid values: EQUAL, CONTAINS, NOT_EQUAL");
                }
            }
        };
    }
}
