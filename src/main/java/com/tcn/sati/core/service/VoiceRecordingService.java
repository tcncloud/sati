package com.tcn.sati.core.service;

import com.tcn.sati.core.service.dto.SuccessResult;
import com.tcn.sati.core.service.dto.VoiceRecordingDto.CreateLabelRequest;
import com.tcn.sati.core.service.dto.VoiceRecordingDto.DownloadLink;
import com.tcn.sati.core.service.dto.VoiceRecordingDto.DownloadLinkRequest;
import com.tcn.sati.core.service.dto.VoiceRecordingDto.RecordingInfo;
import com.tcn.sati.core.service.dto.VoiceRecordingDto.SearchRecordingsRequest;
import com.tcn.sati.infra.gate.GateClient;

import build.buf.gen.tcnapi.exile.gate.v2.SearchOption;
import build.buf.gen.tcnapi.exile.gate.v2.Operator;
import build.buf.gen.tcnapi.exile.gate.v2.Recording;

import java.util.ArrayList;
import java.util.List;

/**
 * Voice recording service. Subclass to override behavior.
 */
public class VoiceRecordingService {
    protected final GateClient gate;

    public VoiceRecordingService(GateClient gate) {
        this.gate = gate;
    }

    public List<RecordingInfo> search(SearchRecordingsRequest request) {
        var searchOptionsList = new ArrayList<SearchOption>();
        for (String option : request.searchOptions) {
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
        var recordings = new ArrayList<RecordingInfo>();
        while (resIterator.hasNext()) {
            var response = resIterator.next();
            for (Recording r : response.getRecordingsList()) {
                var rec = new RecordingInfo();
                rec.recordingId = r.getRecordingId();
                rec.callSid = r.getCallSid();
                rec.callType = r.getCallType().name()
                        .replace("CALL_TYPE_", "").toLowerCase();
                rec.startTime = r.hasStartTime()
                        ? java.time.Instant.ofEpochSecond(
                                r.getStartTime().getSeconds(),
                                r.getStartTime().getNanos()).toString()
                        : null;
                rec.startOffset = r.hasStartOffset()
                        ? formatDurationSeconds(r.getStartOffset())
                        : null;
                rec.endOffset = r.hasEndOffset()
                        ? formatDurationSeconds(r.getEndOffset())
                        : null;
                rec.duration = r.hasDuration()
                        ? formatDurationSeconds(r.getDuration())
                        : null;
                rec.agentPhone = r.getAgentPhone();
                rec.clientPhone = r.getClientPhone();
                rec.campaign = emptyToNull(r.getCampaign());
                rec.partnerAgentIds = r.getPartnerAgentIdsList();
                rec.label = emptyToNull(r.getLabel());
                rec.value = emptyToNull(r.getValue());
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
        String frac = String.format("%09d", nanos).replaceAll("0+$", "");
        return seconds + "." + frac;
    }

    private static String emptyToNull(String s) {
        return (s == null || s.isEmpty()) ? null : s;
    }

    public DownloadLink getDownloadLink(DownloadLinkRequest request) {
        var reqBuilder = build.buf.gen.tcnapi.exile.gate.v2.GetVoiceRecordingDownloadLinkRequest.newBuilder()
                .setRecordingId(request.recordingId);
        if (request.startOffset != null && !request.startOffset.isBlank())
            reqBuilder.setStartOffset(parseDuration(request.startOffset));
        if (request.endOffset != null && !request.endOffset.isBlank())
            reqBuilder.setEndOffset(parseDuration(request.endOffset));

        var resp = gate.getVoiceRecordingDownloadLink(reqBuilder.build());
        var result = new DownloadLink();
        result.downloadLink = resp.getDownloadLink();
        result.playbackLink = emptyToNull(resp.getPlaybackLink());
        return result;
    }

    public Object listSearchableFields() {
        var resp = gate.listSearchableRecordingFields(
                build.buf.gen.tcnapi.exile.gate.v2.ListSearchableRecordingFieldsRequest.newBuilder().build());
        return resp.getFieldsList();
    }

    public SuccessResult createLabel(CreateLabelRequest request) {
        var builder = build.buf.gen.tcnapi.exile.gate.v2.CreateRecordingLabelRequest.newBuilder()
                .setCallSid(request.callSid)
                .setKey(request.key)
                .setValue(request.value);
        gate.createRecordingLabel(builder.build());
        return new SuccessResult();
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
