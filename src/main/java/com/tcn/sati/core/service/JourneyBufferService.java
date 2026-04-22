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
        var recordBuilder = build.buf.gen.tcnapi.exile.gate.v3.Record.newBuilder()
                .setPoolId(request.record.poolId)
                .setRecordId(request.record.recordId)
                .setPayload(mapToStruct(request.record.jsonRecordPayload));

        var resp = gate.addRecordToJourneyBuffer(
                build.buf.gen.tcnapi.exile.gate.v3.AddRecordToJourneyBufferRequest.newBuilder()
                        .setRecord(recordBuilder.build())
                        .build());

        String status = resp.getStatus().name();
        return new AddRecordResponse(status);
    }

    private static com.google.protobuf.Struct mapToStruct(java.util.Map<String, Object> map) {
        var builder = com.google.protobuf.Struct.newBuilder();
        if (map != null) {
            for (var entry : map.entrySet()) {
                builder.putFields(entry.getKey(), objectToValue(entry.getValue()));
            }
        }
        return builder.build();
    }

    @SuppressWarnings("unchecked")
    private static com.google.protobuf.Value objectToValue(Object obj) {
        if (obj == null)
            return com.google.protobuf.Value.newBuilder()
                    .setNullValue(com.google.protobuf.NullValue.NULL_VALUE).build();
        if (obj instanceof String s)
            return com.google.protobuf.Value.newBuilder().setStringValue(s).build();
        if (obj instanceof Number n)
            return com.google.protobuf.Value.newBuilder().setNumberValue(n.doubleValue()).build();
        if (obj instanceof Boolean b)
            return com.google.protobuf.Value.newBuilder().setBoolValue(b).build();
        if (obj instanceof java.util.Map<?, ?> m)
            return com.google.protobuf.Value.newBuilder()
                    .setStructValue(mapToStruct((java.util.Map<String, Object>) m)).build();
        if (obj instanceof java.util.List<?> l) {
            var lb = com.google.protobuf.ListValue.newBuilder();
            for (Object item : l) lb.addValues(objectToValue(item));
            return com.google.protobuf.Value.newBuilder().setListValue(lb).build();
        }
        return com.google.protobuf.Value.newBuilder().setStringValue(obj.toString()).build();
    }
}
