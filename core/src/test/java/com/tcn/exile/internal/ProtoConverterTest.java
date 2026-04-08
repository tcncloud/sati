package com.tcn.exile.internal;

import static org.junit.jupiter.api.Assertions.*;

import com.tcn.exile.model.*;
import com.tcn.exile.model.event.*;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ProtoConverterTest {

  // ---- Duration ----

  @Test
  void durationRoundTrip() {
    var java = Duration.ofSeconds(90, 500_000_000);
    var proto = ProtoConverter.fromDuration(java);
    assertEquals(90, proto.getSeconds());
    assertEquals(500_000_000, proto.getNanos());
    assertEquals(java, ProtoConverter.toDuration(proto));
  }

  @Test
  void durationNullReturnsDefault() {
    var proto = ProtoConverter.fromDuration(null);
    assertEquals(0, proto.getSeconds());
    assertEquals(Duration.ZERO, ProtoConverter.toDuration(null));
  }

  @Test
  void durationDefaultInstanceReturnsZero() {
    assertEquals(
        Duration.ZERO,
        ProtoConverter.toDuration(com.google.protobuf.Duration.getDefaultInstance()));
  }

  // ---- Timestamp ----

  @Test
  void instantRoundTrip() {
    var java = Instant.parse("2026-01-15T10:30:00Z");
    var proto = ProtoConverter.fromInstant(java);
    assertEquals(java.getEpochSecond(), proto.getSeconds());
    assertEquals(java, ProtoConverter.toInstant(proto));
  }

  @Test
  void instantNullReturnsNull() {
    assertNull(ProtoConverter.toInstant(null));
    var proto = ProtoConverter.fromInstant(null);
    assertEquals(0, proto.getSeconds());
  }

  // ---- CallType ----

  @Test
  void callTypeMapping() {
    assertEquals(
        CallType.INBOUND,
        ProtoConverter.toCallType(build.buf.gen.tcnapi.exile.v3.CallType.CALL_TYPE_INBOUND));
    assertEquals(
        CallType.OUTBOUND,
        ProtoConverter.toCallType(build.buf.gen.tcnapi.exile.v3.CallType.CALL_TYPE_OUTBOUND));
    assertEquals(
        CallType.PREVIEW,
        ProtoConverter.toCallType(build.buf.gen.tcnapi.exile.v3.CallType.CALL_TYPE_PREVIEW));
    assertEquals(
        CallType.MANUAL,
        ProtoConverter.toCallType(build.buf.gen.tcnapi.exile.v3.CallType.CALL_TYPE_MANUAL));
    assertEquals(
        CallType.MAC,
        ProtoConverter.toCallType(build.buf.gen.tcnapi.exile.v3.CallType.CALL_TYPE_MAC));
    assertEquals(
        CallType.UNSPECIFIED,
        ProtoConverter.toCallType(build.buf.gen.tcnapi.exile.v3.CallType.CALL_TYPE_UNSPECIFIED));
  }

  // ---- AgentState ----

  @Test
  void agentStateRoundTrip() {
    var proto = build.buf.gen.tcnapi.exile.v3.AgentState.AGENT_STATE_READY;
    var java = ProtoConverter.toAgentState(proto);
    assertEquals(AgentState.READY, java);
    assertEquals(proto, ProtoConverter.fromAgentState(java));
  }

  @Test
  void agentStateUnknownReturnsUnspecified() {
    assertEquals(
        AgentState.UNSPECIFIED,
        ProtoConverter.toAgentState(
            build.buf.gen.tcnapi.exile.v3.AgentState.AGENT_STATE_UNSPECIFIED));
  }

  // ---- Pool ----

  @Test
  void poolRoundTrip() {
    var java = new Pool("P-1", "Test Pool", Pool.PoolStatus.READY, 42);
    var proto = ProtoConverter.fromPool(java);
    assertEquals("P-1", proto.getPoolId());
    assertEquals("Test Pool", proto.getDescription());
    assertEquals(42, proto.getRecordCount());
    assertEquals(
        build.buf.gen.tcnapi.exile.v3.Pool.PoolStatus.POOL_STATUS_READY, proto.getStatus());

    var back = ProtoConverter.toPool(proto);
    assertEquals(java, back);
  }

  @Test
  void poolAllStatuses() {
    for (var status : Pool.PoolStatus.values()) {
      var java = new Pool("P-1", "desc", status, 0);
      var back = ProtoConverter.toPool(ProtoConverter.fromPool(java));
      assertEquals(status, back.status());
    }
  }

  // ---- Record ----

  @Test
  void dataRecordRoundTrip() {
    var java = new DataRecord("P-1", "R-1", Map.of("name", "John", "age", 30.0));
    var proto = ProtoConverter.fromRecord(java);
    assertEquals("P-1", proto.getPoolId());
    assertEquals("R-1", proto.getRecordId());
    assertTrue(proto.hasPayload());

    var back = ProtoConverter.toRecord(proto);
    assertEquals("P-1", back.poolId());
    assertEquals("R-1", back.recordId());
    assertEquals("John", back.payload().get("name"));
    assertEquals(30.0, back.payload().get("age"));
  }

  // ---- Field ----

  @Test
  void fieldRoundTrip() {
    var java = new Field("fname", "John", "P-1", "R-1");
    var proto = ProtoConverter.fromField(java);
    var back = ProtoConverter.toField(proto);
    assertEquals(java, back);
  }

  // ---- Filter ----

  @Test
  void filterRoundTrip() {
    for (var op : Filter.Operator.values()) {
      var java = new Filter("field1", op, "value1");
      var proto = ProtoConverter.fromFilter(java);
      var back = ProtoConverter.toFilter(proto);
      assertEquals(java, back);
    }
  }

  // ---- Struct / Map ----

  @Test
  void structMapRoundTrip() {
    var map = Map.of("string", (Object) "hello", "number", 42.0, "bool", true);
    var struct = ProtoConverter.mapToStruct(map);
    var back = ProtoConverter.structToMap(struct);
    assertEquals("hello", back.get("string"));
    assertEquals(42.0, back.get("number"));
    assertEquals(true, back.get("bool"));
  }

  @Test
  void structMapHandlesNull() {
    var map = ProtoConverter.structToMap(null);
    assertTrue(map.isEmpty());
    var struct = ProtoConverter.mapToStruct(null);
    assertEquals(com.google.protobuf.Struct.getDefaultInstance(), struct);
  }

  @Test
  void structMapHandlesNestedStructs() {
    var nested = Map.of("inner", (Object) "value");
    var map = Map.of("outer", (Object) nested);
    var struct = ProtoConverter.mapToStruct(map);
    var back = ProtoConverter.structToMap(struct);
    @SuppressWarnings("unchecked")
    var innerBack = (Map<String, Object>) back.get("outer");
    assertEquals("value", innerBack.get("inner"));
  }

  @Test
  void structMapHandlesLists() {
    var list = List.of("a", "b", "c");
    var map = Map.of("items", (Object) list);
    var struct = ProtoConverter.mapToStruct(map);
    var back = ProtoConverter.structToMap(struct);
    @SuppressWarnings("unchecked")
    var listBack = (List<Object>) back.get("items");
    assertEquals(3, listBack.size());
    assertEquals("a", listBack.get(0));
  }

  @Test
  void valueToObjectHandlesNull() {
    assertNull(ProtoConverter.valueToObject(null));
    var nullValue =
        com.google.protobuf.Value.newBuilder()
            .setNullValue(com.google.protobuf.NullValue.NULL_VALUE)
            .build();
    assertNull(ProtoConverter.valueToObject(nullValue));
  }

  // ---- TaskData ----

  @Test
  void taskDataConversion() {
    var protoList =
        List.of(
            build.buf.gen.tcnapi.exile.v3.TaskData.newBuilder()
                .setKey("pool_id")
                .setValue(com.google.protobuf.Value.newBuilder().setStringValue("P-1").build())
                .build(),
            build.buf.gen.tcnapi.exile.v3.TaskData.newBuilder()
                .setKey("count")
                .setValue(com.google.protobuf.Value.newBuilder().setNumberValue(5.0).build())
                .build());
    var java = ProtoConverter.toTaskData(protoList);
    assertEquals(2, java.size());
    assertEquals("pool_id", java.get(0).key());
    assertEquals("P-1", java.get(0).value());
    assertEquals("count", java.get(1).key());
    assertEquals(5.0, java.get(1).value());
  }

  // ---- Agent ----

  @Test
  void agentConversion() {
    var proto =
        build.buf.gen.tcnapi.exile.v3.Agent.newBuilder()
            .setUserId("U-1")
            .setOrgId("O-1")
            .setFirstName("John")
            .setLastName("Doe")
            .setUsername("jdoe")
            .setPartnerAgentId("PA-1")
            .setCurrentSessionId("S-1")
            .setAgentState(build.buf.gen.tcnapi.exile.v3.AgentState.AGENT_STATE_READY)
            .setIsLoggedIn(true)
            .setIsMuted(false)
            .setIsRecording(true)
            .setConnectedParty(
                build.buf.gen.tcnapi.exile.v3.Agent.ConnectedParty.newBuilder()
                    .setCallSid(42)
                    .setCallType(build.buf.gen.tcnapi.exile.v3.CallType.CALL_TYPE_INBOUND)
                    .setIsInbound(true))
            .build();

    var java = ProtoConverter.toAgent(proto);
    assertEquals("U-1", java.userId());
    assertEquals("O-1", java.orgId());
    assertEquals("John", java.firstName());
    assertEquals("jdoe", java.username());
    assertEquals(AgentState.READY, java.state());
    assertTrue(java.loggedIn());
    assertFalse(java.muted());
    assertTrue(java.recording());
    assertTrue(java.connectedParty().isPresent());
    assertEquals(42, java.connectedParty().get().callSid());
    assertEquals(CallType.INBOUND, java.connectedParty().get().callType());
  }

  @Test
  void agentWithoutConnectedParty() {
    var proto =
        build.buf.gen.tcnapi.exile.v3.Agent.newBuilder().setUserId("U-1").setOrgId("O-1").build();
    var java = ProtoConverter.toAgent(proto);
    assertTrue(java.connectedParty().isEmpty());
  }

  // ---- Skill ----

  @Test
  void skillWithProficiency() {
    var proto =
        build.buf.gen.tcnapi.exile.v3.Skill.newBuilder()
            .setSkillId("SK-1")
            .setName("Spanish")
            .setDescription("Spanish language")
            .setProficiency(8)
            .build();
    var java = ProtoConverter.toSkill(proto);
    assertEquals("SK-1", java.skillId());
    assertEquals("Spanish", java.name());
    assertTrue(java.proficiency().isPresent());
    assertEquals(8, java.proficiency().getAsLong());
  }

  // ---- Events ----

  @Test
  void agentCallEventConversion() {
    var proto =
        build.buf.gen.tcnapi.exile.v3.AgentCall.newBuilder()
            .setAgentCallSid(1)
            .setCallSid(2)
            .setCallType(build.buf.gen.tcnapi.exile.v3.CallType.CALL_TYPE_OUTBOUND)
            .setOrgId("O-1")
            .setUserId("U-1")
            .setPartnerAgentId("PA-1")
            .setTalkDuration(com.google.protobuf.Duration.newBuilder().setSeconds(120))
            .setCreateTime(com.google.protobuf.Timestamp.newBuilder().setSeconds(1700000000))
            .addTaskData(
                build.buf.gen.tcnapi.exile.v3.TaskData.newBuilder()
                    .setKey("pool_id")
                    .setValue(com.google.protobuf.Value.newBuilder().setStringValue("P-1")))
            .build();

    var java = ProtoConverter.toAgentCallEvent(proto);
    assertEquals(1, java.agentCallSid());
    assertEquals(2, java.callSid());
    assertEquals(CallType.OUTBOUND, java.callType());
    assertEquals("O-1", java.orgId());
    assertEquals("PA-1", java.partnerAgentId());
    assertEquals(Duration.ofSeconds(120), java.talkDuration());
    assertNotNull(java.createTime());
    assertEquals(1, java.taskData().size());
    assertEquals("pool_id", java.taskData().get(0).key());
  }

  @Test
  void telephonyResultEventConversion() {
    var proto =
        build.buf.gen.tcnapi.exile.v3.TelephonyResult.newBuilder()
            .setCallSid(42)
            .setCallType(build.buf.gen.tcnapi.exile.v3.CallType.CALL_TYPE_INBOUND)
            .setOrgId("O-1")
            .setCallerId("+15551234567")
            .setPhoneNumber("+15559876543")
            .setPoolId("P-1")
            .setRecordId("R-1")
            .setStatus(build.buf.gen.tcnapi.exile.v3.TelephonyStatus.TELEPHONY_STATUS_COMPLETED)
            .setOutcome(
                build.buf.gen.tcnapi.exile.v3.TelephonyOutcome.newBuilder()
                    .setCategory(
                        build.buf.gen.tcnapi.exile.v3.TelephonyOutcome.Category.CATEGORY_ANSWERED)
                    .setDetail(
                        build.buf.gen.tcnapi.exile.v3.TelephonyOutcome.Detail.DETAIL_GENERIC))
            .build();

    var java = ProtoConverter.toTelephonyResultEvent(proto);
    assertEquals(42, java.callSid());
    assertEquals(CallType.INBOUND, java.callType());
    assertEquals("+15551234567", java.callerId());
    assertEquals("TELEPHONY_STATUS_COMPLETED", java.status());
    assertEquals("CATEGORY_ANSWERED", java.outcomeCategory());
    assertEquals("DETAIL_GENERIC", java.outcomeDetail());
  }

  @Test
  void callRecordingEventConversion() {
    var proto =
        build.buf.gen.tcnapi.exile.v3.CallRecording.newBuilder()
            .setRecordingId("REC-1")
            .setOrgId("O-1")
            .setCallSid(42)
            .setCallType(build.buf.gen.tcnapi.exile.v3.CallType.CALL_TYPE_INBOUND)
            .setDuration(com.google.protobuf.Duration.newBuilder().setSeconds(300))
            .setRecordingType(
                build.buf.gen.tcnapi.exile.v3.CallRecording.RecordingType.RECORDING_TYPE_TCN)
            .build();

    var java = ProtoConverter.toCallRecordingEvent(proto);
    assertEquals("REC-1", java.recordingId());
    assertEquals(42, java.callSid());
    assertEquals(Duration.ofSeconds(300), java.duration());
    assertEquals("RECORDING_TYPE_TCN", java.recordingType());
  }

  @Test
  void taskEventConversion() {
    var proto =
        build.buf.gen.tcnapi.exile.v3.ExileTask.newBuilder()
            .setTaskSid(1)
            .setTaskGroupSid(2)
            .setOrgId("O-1")
            .setPoolId("P-1")
            .setRecordId("R-1")
            .setAttempts(3)
            .setStatus(build.buf.gen.tcnapi.exile.v3.ExileTask.TaskStatus.TASK_STATUS_RUNNING)
            .build();

    var java = ProtoConverter.toTaskEvent(proto);
    assertEquals(1, java.taskSid());
    assertEquals(2, java.taskGroupSid());
    assertEquals("P-1", java.poolId());
    assertEquals(3, java.attempts());
    assertEquals("TASK_STATUS_RUNNING", java.status());
  }
}
