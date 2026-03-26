package com.tcn.sati.core.service;

import com.tcn.sati.core.service.dto.JourneyBufferDto.AddRecordRequest;
import com.tcn.sati.core.service.dto.JourneyBufferDto.AddRecordResponse;
import com.tcn.sati.infra.gate.GateClient;

/**
 * Journey buffer service. Subclass to override behavior.
 */
public class JourneyBufferService {
    protected final GateClient gate;

    public JourneyBufferService(GateClient gate) {
        this.gate = gate;
    }

    public AddRecordResponse addRecord(AddRecordRequest request) {
        String jsonPayload;
        try {
            jsonPayload = new com.fasterxml.jackson.databind.ObjectMapper()
                    .writeValueAsString(request.record.jsonRecordPayload);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to serialize jsonRecordPayload", e);
        }

        var recordBuilder = build.buf.gen.tcnapi.exile.core.v2.Record.newBuilder()
                .setPoolId(request.record.poolId)
                .setRecordId(request.record.recordId)
                .setJsonRecordPayload(jsonPayload);

        var resp = gate.addRecordToJourneyBuffer(
                build.buf.gen.tcnapi.exile.gate.v2.AddRecordToJourneyBufferRequest.newBuilder()
                        .setRecord(recordBuilder.build())
                        .build());

        // Return status from proto response
        String status = resp.getStatus().name();
        return new AddRecordResponse(status);
    }
}
