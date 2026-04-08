package com.tcn.exile.service;

import com.tcn.exile.internal.ProtoConverter;
import com.tcn.exile.model.DataRecord;
import io.grpc.ManagedChannel;

/** Journey buffer operations. No proto types in the public API. */
public final class JourneyService {

  private final build.buf.gen.tcnapi.exile.gate.v3.JourneyServiceGrpc.JourneyServiceBlockingStub
      stub;

  JourneyService(ManagedChannel channel) {
    this.stub = build.buf.gen.tcnapi.exile.gate.v3.JourneyServiceGrpc.newBlockingStub(channel);
  }

  public enum JourneyBufferStatus {
    INSERTED,
    UPDATED,
    IGNORED,
    REJECTED,
    UNSPECIFIED
  }

  public JourneyBufferStatus addRecordToJourneyBuffer(DataRecord record) {
    var resp =
        stub.addRecordToJourneyBuffer(
            build.buf.gen.tcnapi.exile.gate.v3.AddRecordToJourneyBufferRequest.newBuilder()
                .setRecord(ProtoConverter.fromRecord(record))
                .build());
    return switch (resp.getStatus()) {
      case JOURNEY_BUFFER_STATUS_INSERTED -> JourneyBufferStatus.INSERTED;
      case JOURNEY_BUFFER_STATUS_UPDATED -> JourneyBufferStatus.UPDATED;
      case JOURNEY_BUFFER_STATUS_IGNORED -> JourneyBufferStatus.IGNORED;
      case JOURNEY_BUFFER_STATUS_REJECTED -> JourneyBufferStatus.REJECTED;
      default -> JourneyBufferStatus.UNSPECIFIED;
    };
  }
}
