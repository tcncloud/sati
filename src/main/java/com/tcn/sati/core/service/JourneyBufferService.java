package com.tcn.sati.core.service;

import com.tcn.sati.core.service.dto.JourneyBufferDto.AddRecordRequest;
import com.tcn.sati.core.service.dto.SuccessResult;
import com.tcn.sati.infra.gate.GateClient;

/**
 * Journey buffer service. Subclass to override behavior.
 */
public class JourneyBufferService {
    protected final GateClient gate;

    public JourneyBufferService(GateClient gate) {
        this.gate = gate;
    }

    public SuccessResult addRecord(AddRecordRequest request) {
        String jsonPayload;
        try {
            jsonPayload = new com.fasterxml.jackson.databind.ObjectMapper()
                    .writeValueAsString(request.jsonRecordPayload);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to serialize jsonRecordPayload", e);
        }

        var recordBuilder = build.buf.gen.tcnapi.exile.core.v2.Record.newBuilder()
                .setPoolId(request.poolId)
                .setRecordId(request.recordId)
                .setJsonRecordPayload(jsonPayload);

        gate.addRecordToJourneyBuffer(
                build.buf.gen.tcnapi.exile.gate.v2.AddRecordToJourneyBufferRequest.newBuilder()
                        .setRecord(recordBuilder.build())
                        .build());
        return new SuccessResult();
    }
}
