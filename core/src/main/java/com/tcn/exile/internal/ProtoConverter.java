package com.tcn.exile.internal;

import com.tcn.exile.model.*;
import com.tcn.exile.model.DataRecord;
import com.tcn.exile.model.event.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Converts between proto types and the public Java model. This is the only place in the library
 * that touches generated proto classes directly.
 */
public final class ProtoConverter {

  private ProtoConverter() {}

  // ---- Duration / Timestamp ----

  public static Duration toDuration(com.google.protobuf.Duration d) {
    if (d == null || d.equals(com.google.protobuf.Duration.getDefaultInstance()))
      return Duration.ZERO;
    return Duration.ofSeconds(d.getSeconds(), d.getNanos());
  }

  public static com.google.protobuf.Duration fromDuration(Duration d) {
    if (d == null) return com.google.protobuf.Duration.getDefaultInstance();
    return com.google.protobuf.Duration.newBuilder()
        .setSeconds(d.getSeconds())
        .setNanos(d.getNano())
        .build();
  }

  public static Instant toInstant(com.google.protobuf.Timestamp t) {
    if (t == null || t.equals(com.google.protobuf.Timestamp.getDefaultInstance())) return null;
    return Instant.ofEpochSecond(t.getSeconds(), t.getNanos());
  }

  public static com.google.protobuf.Timestamp fromInstant(Instant i) {
    if (i == null) return com.google.protobuf.Timestamp.getDefaultInstance();
    return com.google.protobuf.Timestamp.newBuilder()
        .setSeconds(i.getEpochSecond())
        .setNanos(i.getNano())
        .build();
  }

  // ---- Enums ----

  public static CallType toCallType(build.buf.gen.tcnapi.exile.gate.v3.CallType ct) {
    return switch (ct) {
      case CALL_TYPE_INBOUND -> CallType.INBOUND;
      case CALL_TYPE_OUTBOUND -> CallType.OUTBOUND;
      case CALL_TYPE_PREVIEW -> CallType.PREVIEW;
      case CALL_TYPE_MANUAL -> CallType.MANUAL;
      case CALL_TYPE_MAC -> CallType.MAC;
      default -> CallType.UNSPECIFIED;
    };
  }

  public static AgentState toAgentState(build.buf.gen.tcnapi.exile.gate.v3.AgentState as) {
    try {
      return AgentState.valueOf(as.name().replace("AGENT_STATE_", ""));
    } catch (IllegalArgumentException e) {
      return AgentState.UNSPECIFIED;
    }
  }

  public static build.buf.gen.tcnapi.exile.gate.v3.AgentState fromAgentState(AgentState as) {
    try {
      return build.buf.gen.tcnapi.exile.gate.v3.AgentState.valueOf("AGENT_STATE_" + as.name());
    } catch (IllegalArgumentException e) {
      return build.buf.gen.tcnapi.exile.gate.v3.AgentState.AGENT_STATE_UNSPECIFIED;
    }
  }

  // ---- Core types ----

  public static Pool toPool(build.buf.gen.tcnapi.exile.gate.v3.Pool p) {
    return new Pool(
        p.getPoolId(),
        p.getDescription(),
        switch (p.getStatus()) {
          case POOL_STATUS_READY -> Pool.PoolStatus.READY;
          case POOL_STATUS_NOT_READY -> Pool.PoolStatus.NOT_READY;
          case POOL_STATUS_BUSY -> Pool.PoolStatus.BUSY;
          default -> Pool.PoolStatus.UNSPECIFIED;
        },
        p.getRecordCount());
  }

  public static build.buf.gen.tcnapi.exile.gate.v3.Pool fromPool(Pool p) {
    return build.buf.gen.tcnapi.exile.gate.v3.Pool.newBuilder()
        .setPoolId(p.poolId())
        .setDescription(p.description())
        .setStatus(
            switch (p.status()) {
              case READY -> build.buf.gen.tcnapi.exile.gate.v3.Pool.PoolStatus.POOL_STATUS_READY;
              case NOT_READY ->
                  build.buf.gen.tcnapi.exile.gate.v3.Pool.PoolStatus.POOL_STATUS_NOT_READY;
              case BUSY -> build.buf.gen.tcnapi.exile.gate.v3.Pool.PoolStatus.POOL_STATUS_BUSY;
              default -> build.buf.gen.tcnapi.exile.gate.v3.Pool.PoolStatus.POOL_STATUS_UNSPECIFIED;
            })
        .setRecordCount(p.recordCount())
        .build();
  }

  public static DataRecord toRecord(build.buf.gen.tcnapi.exile.gate.v3.Record r) {
    return new DataRecord(r.getPoolId(), r.getRecordId(), structToMap(r.getPayload()));
  }

  public static build.buf.gen.tcnapi.exile.gate.v3.Record fromRecord(DataRecord r) {
    return build.buf.gen.tcnapi.exile.gate.v3.Record.newBuilder()
        .setPoolId(r.poolId())
        .setRecordId(r.recordId())
        .setPayload(mapToStruct(r.payload()))
        .build();
  }

  public static Field toField(build.buf.gen.tcnapi.exile.gate.v3.Field f) {
    return new Field(f.getFieldName(), f.getFieldValue(), f.getPoolId(), f.getRecordId());
  }

  public static build.buf.gen.tcnapi.exile.gate.v3.Field fromField(Field f) {
    return build.buf.gen.tcnapi.exile.gate.v3.Field.newBuilder()
        .setFieldName(f.fieldName())
        .setFieldValue(f.fieldValue())
        .setPoolId(f.poolId())
        .setRecordId(f.recordId())
        .build();
  }

  public static Filter toFilter(build.buf.gen.tcnapi.exile.gate.v3.Filter f) {
    return new Filter(
        f.getField(),
        switch (f.getOperator()) {
          case OPERATOR_EQUAL -> Filter.Operator.EQUAL;
          case OPERATOR_NOT_EQUAL -> Filter.Operator.NOT_EQUAL;
          case OPERATOR_CONTAINS -> Filter.Operator.CONTAINS;
          case OPERATOR_GREATER_THAN -> Filter.Operator.GREATER_THAN;
          case OPERATOR_LESS_THAN -> Filter.Operator.LESS_THAN;
          case OPERATOR_IN -> Filter.Operator.IN;
          case OPERATOR_EXISTS -> Filter.Operator.EXISTS;
          default -> Filter.Operator.UNSPECIFIED;
        },
        f.getValue());
  }

  public static build.buf.gen.tcnapi.exile.gate.v3.Filter fromFilter(Filter f) {
    return build.buf.gen.tcnapi.exile.gate.v3.Filter.newBuilder()
        .setField(f.field())
        .setOperator(
            switch (f.operator()) {
              case EQUAL -> build.buf.gen.tcnapi.exile.gate.v3.Filter.Operator.OPERATOR_EQUAL;
              case NOT_EQUAL ->
                  build.buf.gen.tcnapi.exile.gate.v3.Filter.Operator.OPERATOR_NOT_EQUAL;
              case CONTAINS -> build.buf.gen.tcnapi.exile.gate.v3.Filter.Operator.OPERATOR_CONTAINS;
              case GREATER_THAN ->
                  build.buf.gen.tcnapi.exile.gate.v3.Filter.Operator.OPERATOR_GREATER_THAN;
              case LESS_THAN ->
                  build.buf.gen.tcnapi.exile.gate.v3.Filter.Operator.OPERATOR_LESS_THAN;
              case IN -> build.buf.gen.tcnapi.exile.gate.v3.Filter.Operator.OPERATOR_IN;
              case EXISTS -> build.buf.gen.tcnapi.exile.gate.v3.Filter.Operator.OPERATOR_EXISTS;
              default -> build.buf.gen.tcnapi.exile.gate.v3.Filter.Operator.OPERATOR_UNSPECIFIED;
            })
        .setValue(f.value())
        .build();
  }

  public static List<TaskData> toTaskData(List<build.buf.gen.tcnapi.exile.gate.v3.TaskData> list) {
    return list.stream()
        .map(td -> new TaskData(td.getKey(), valueToObject(td.getValue())))
        .collect(Collectors.toList());
  }

  // ---- Agent ----

  public static Agent toAgent(build.buf.gen.tcnapi.exile.gate.v3.Agent a) {
    Optional<Agent.ConnectedParty> cp =
        a.hasConnectedParty()
            ? Optional.of(
                new Agent.ConnectedParty(
                    a.getConnectedParty().getCallSid(),
                    toCallType(a.getConnectedParty().getCallType()),
                    a.getConnectedParty().getIsInbound()))
            : Optional.empty();
    return new Agent(
        a.getUserId(),
        a.getOrgId(),
        a.getFirstName(),
        a.getLastName(),
        a.getUsername(),
        a.getPartnerAgentId(),
        a.getCurrentSessionId(),
        toAgentState(a.getAgentState()),
        a.getIsLoggedIn(),
        a.getIsMuted(),
        a.getIsRecording(),
        cp);
  }

  public static Skill toSkill(build.buf.gen.tcnapi.exile.gate.v3.Skill s) {
    return new Skill(
        s.getSkillId(),
        s.getName(),
        s.getDescription(),
        s.hasProficiency() ? OptionalLong.of(s.getProficiency()) : OptionalLong.empty());
  }

  // ---- Events ----

  public static AgentCallEvent toAgentCallEvent(build.buf.gen.tcnapi.exile.gate.v3.AgentCall ac) {
    return new AgentCallEvent(
        ac.getAgentCallSid(),
        ac.getCallSid(),
        toCallType(ac.getCallType()),
        ac.getOrgId(),
        ac.getUserId(),
        ac.getPartnerAgentId(),
        ac.getInternalKey(),
        toDuration(ac.getTalkDuration()),
        toDuration(ac.getWaitDuration()),
        toDuration(ac.getWrapupDuration()),
        toDuration(ac.getPauseDuration()),
        toDuration(ac.getTransferDuration()),
        toDuration(ac.getManualDuration()),
        toDuration(ac.getPreviewDuration()),
        toDuration(ac.getHoldDuration()),
        toDuration(ac.getAgentWaitDuration()),
        toDuration(ac.getSuspendedDuration()),
        toDuration(ac.getExternalTransferDuration()),
        toInstant(ac.getCreateTime()),
        toInstant(ac.getUpdateTime()),
        toTaskData(ac.getTaskDataList()));
  }

  public static TelephonyResultEvent toTelephonyResultEvent(
      build.buf.gen.tcnapi.exile.gate.v3.TelephonyResult tr) {
    return new TelephonyResultEvent(
        tr.getCallSid(),
        toCallType(tr.getCallType()),
        tr.getOrgId(),
        tr.getInternalKey(),
        tr.getCallerId(),
        tr.getPhoneNumber(),
        tr.getPoolId(),
        tr.getRecordId(),
        tr.getClientSid(),
        tr.getStatus().name(),
        tr.hasOutcome() ? tr.getOutcome().getCategory().name() : "",
        tr.hasOutcome() ? tr.getOutcome().getDetail().name() : "",
        toDuration(tr.getDeliveryLength()),
        toDuration(tr.getLinkbackLength()),
        toInstant(tr.getCreateTime()),
        toInstant(tr.getUpdateTime()),
        toInstant(tr.getStartTime()),
        toInstant(tr.getEndTime()),
        toTaskData(tr.getTaskDataList()));
  }

  public static AgentResponseEvent toAgentResponseEvent(
      build.buf.gen.tcnapi.exile.gate.v3.AgentResponse ar) {
    return new AgentResponseEvent(
        ar.getAgentCallResponseSid(),
        ar.getCallSid(),
        toCallType(ar.getCallType()),
        ar.getOrgId(),
        ar.getUserId(),
        ar.getPartnerAgentId(),
        ar.getInternalKey(),
        ar.getAgentSid(),
        ar.getClientSid(),
        ar.getResponseKey(),
        ar.getResponseValue(),
        toInstant(ar.getCreateTime()),
        toInstant(ar.getUpdateTime()));
  }

  public static CallRecordingEvent toCallRecordingEvent(
      build.buf.gen.tcnapi.exile.gate.v3.CallRecording cr) {
    return new CallRecordingEvent(
        cr.getRecordingId(),
        cr.getOrgId(),
        cr.getCallSid(),
        toCallType(cr.getCallType()),
        toDuration(cr.getDuration()),
        cr.getRecordingType().name(),
        toInstant(cr.getStartTime()));
  }

  public static TransferInstanceEvent toTransferInstanceEvent(
      build.buf.gen.tcnapi.exile.gate.v3.TransferInstance ti) {
    var src = ti.getSource();
    return new TransferInstanceEvent(
        ti.getClientSid(),
        ti.getOrgId(),
        ti.getTransferInstanceId(),
        new TransferInstanceEvent.Source(
            src.getCallSid(),
            toCallType(src.getCallType()),
            src.getPartnerAgentId(),
            src.getUserId(),
            src.getConversationId(),
            src.getSessionSid(),
            src.getAgentCallSid()),
        ti.getTransferType().name(),
        ti.getTransferResult().name(),
        ti.getInitiation().name(),
        toInstant(ti.getCreateTime()),
        toInstant(ti.getTransferTime()),
        toInstant(ti.getAcceptTime()),
        toInstant(ti.getHangupTime()),
        toInstant(ti.getEndTime()),
        toInstant(ti.getUpdateTime()),
        toDuration(ti.getPendingDuration()),
        toDuration(ti.getExternalDuration()),
        toDuration(ti.getFullDuration()));
  }

  public static TaskEvent toTaskEvent(build.buf.gen.tcnapi.exile.gate.v3.ExileTask t) {
    return new TaskEvent(
        t.getTaskSid(),
        t.getTaskGroupSid(),
        t.getOrgId(),
        t.getClientSid(),
        t.getPoolId(),
        t.getRecordId(),
        t.getAttempts(),
        t.getStatus().name(),
        toInstant(t.getCreateTime()),
        toInstant(t.getUpdateTime()));
  }

  // ---- Struct ↔ Map ----

  public static Map<String, Object> structToMap(com.google.protobuf.Struct s) {
    if (s == null) return Map.of();
    Map<String, Object> map = new LinkedHashMap<>();
    s.getFieldsMap().forEach((k, v) -> map.put(k, valueToObject(v)));
    return map;
  }

  public static com.google.protobuf.Struct mapToStruct(Map<String, Object> map) {
    if (map == null || map.isEmpty()) return com.google.protobuf.Struct.getDefaultInstance();
    var builder = com.google.protobuf.Struct.newBuilder();
    map.forEach((k, v) -> builder.putFields(k, objectToValue(v)));
    return builder.build();
  }

  @SuppressWarnings("unchecked")
  public static Object valueToObject(com.google.protobuf.Value v) {
    if (v == null) return null;
    return switch (v.getKindCase()) {
      case STRING_VALUE -> v.getStringValue();
      case NUMBER_VALUE -> v.getNumberValue();
      case BOOL_VALUE -> v.getBoolValue();
      case NULL_VALUE -> null;
      case STRUCT_VALUE -> structToMap(v.getStructValue());
      case LIST_VALUE ->
          v.getListValue().getValuesList().stream()
              .map(ProtoConverter::valueToObject)
              .collect(Collectors.toList());
      default -> null;
    };
  }

  @SuppressWarnings("unchecked")
  public static com.google.protobuf.Value objectToValue(Object obj) {
    if (obj == null) {
      return com.google.protobuf.Value.newBuilder()
          .setNullValue(com.google.protobuf.NullValue.NULL_VALUE)
          .build();
    }
    if (obj instanceof String s) {
      return com.google.protobuf.Value.newBuilder().setStringValue(s).build();
    }
    if (obj instanceof Number n) {
      return com.google.protobuf.Value.newBuilder().setNumberValue(n.doubleValue()).build();
    }
    if (obj instanceof Boolean b) {
      return com.google.protobuf.Value.newBuilder().setBoolValue(b).build();
    }
    if (obj instanceof Map<?, ?> m) {
      return com.google.protobuf.Value.newBuilder()
          .setStructValue(mapToStruct((Map<String, Object>) m))
          .build();
    }
    if (obj instanceof List<?> l) {
      var lb = com.google.protobuf.ListValue.newBuilder();
      for (Object item : l) lb.addValues(objectToValue(item));
      return com.google.protobuf.Value.newBuilder().setListValue(lb).build();
    }
    return com.google.protobuf.Value.newBuilder().setStringValue(obj.toString()).build();
  }
}
