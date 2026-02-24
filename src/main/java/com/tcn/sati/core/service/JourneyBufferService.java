package com.tcn.sati.core.service;

import com.tcn.sati.infra.gate.GateClient;

import java.util.Map;

/**
 * Journey buffer service. Subclass to override behavior.
 */
public class JourneyBufferService {
    protected final GateClient gate;

    public JourneyBufferService(GateClient gate) {
        this.gate = gate;
    }

    public Map<String, Object> addRecord(String poolId, String recordId, String jsonPayload) {
        var recordBuilder = build.buf.gen.tcnapi.exile.core.v2.Record.newBuilder()
                .setPoolId(poolId)
                .setRecordId(recordId)
                .setJsonRecordPayload(jsonPayload);

        gate.addRecordToJourneyBuffer(
                build.buf.gen.tcnapi.exile.gate.v2.AddRecordToJourneyBufferRequest.newBuilder()
                        .setRecord(recordBuilder.build())
                        .build());
        return Map.of("success", true);
    }
}
