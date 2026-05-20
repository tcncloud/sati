package com.tcn.exile.model.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tcn.exile.model.CallType;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TransferInstanceEventTest {

  @Test
  void testTransferInstanceEvent() {
    var sourceCall =
        new TransferInstanceEvent.Source.Call(
            123L, CallType.INBOUND, "agent1", "user1", "conv1", 456L, 789L);
    var source = new TransferInstanceEvent.Source(sourceCall);

    var agent = new TransferInstanceEvent.Destination.Agent(1L, "p1", "u1");
    var outbound =
        new TransferInstanceEvent.Destination.Outbound("12345", 2L, CallType.OUTBOUND, "c2");
    var skills = new TransferInstanceEvent.Destination.Skills(Map.of("skill1", 100L));
    var destination = new TransferInstanceEvent.Destination(agent, outbound, skills);

    var event =
        new TransferInstanceEvent(
            1L,
            "org1",
            "transfer-1",
            source,
            destination,
            "type1",
            "result1",
            "init1",
            Instant.ofEpochSecond(1),
            Instant.ofEpochSecond(2),
            Instant.ofEpochSecond(3),
            Instant.ofEpochSecond(4),
            Instant.ofEpochSecond(5),
            Instant.ofEpochSecond(6),
            Duration.ofSeconds(1),
            Duration.ofSeconds(2),
            Duration.ofSeconds(3),
            true,
            false,
            3000000L,
            2000000L,
            1000000L,
            Instant.ofEpochSecond(7),
            Instant.ofEpochSecond(8),
            Instant.ofEpochSecond(9),
            Instant.ofEpochSecond(10));

    // Base fields
    assertEquals(1L, event.clientSid());
    assertEquals("org1", event.orgId());
    assertEquals("transfer-1", event.transferInstanceId());

    // Source
    assertNotNull(event.source());
    assertNotNull(event.source().call());
    assertEquals(123L, event.source().call().callSid());
    assertEquals(CallType.INBOUND, event.source().call().callType());
    assertEquals("agent1", event.source().call().partnerAgentId());
    assertEquals("user1", event.source().call().userId());
    assertEquals("conv1", event.source().call().conversationId());
    assertEquals(456L, event.source().call().sessionSid());
    assertEquals(789L, event.source().call().agentCallSid());

    // Destination
    assertNotNull(event.destination());
    assertNotNull(event.destination().agent());
    assertEquals(1L, event.destination().agent().sessionSid());
    assertEquals("p1", event.destination().agent().partnerAgentId());
    assertEquals("u1", event.destination().agent().userId());

    assertNotNull(event.destination().outbound());
    assertEquals("12345", event.destination().outbound().phoneNumber());
    assertEquals(2L, event.destination().outbound().callSid());
    assertEquals(CallType.OUTBOUND, event.destination().outbound().callType());
    assertEquals("c2", event.destination().outbound().conversationId());

    assertNotNull(event.destination().skills());
    assertEquals(100L, event.destination().skills().requiredSkills().get("skill1"));

    // Other string fields
    assertEquals("type1", event.transferType());
    assertEquals("result1", event.transferResult());
    assertEquals("init1", event.initiation());

    // v3 Timestamps
    assertEquals(Instant.ofEpochSecond(1), event.createTime());
    assertEquals(Instant.ofEpochSecond(2), event.transferTime());
    assertEquals(Instant.ofEpochSecond(3), event.acceptTime());
    assertEquals(Instant.ofEpochSecond(4), event.hangupTime());
    assertEquals(Instant.ofEpochSecond(5), event.endTime());
    assertEquals(Instant.ofEpochSecond(6), event.updateTime());

    // Durations
    assertEquals(Duration.ofSeconds(1), event.pendingDuration());
    assertEquals(Duration.ofSeconds(2), event.externalDuration());
    assertEquals(Duration.ofSeconds(3), event.fullDuration());

    // v2 Primitives
    assertTrue(event.startAsPending());
    assertFalse(event.startedAsConference());
    assertEquals(3000000L, event.durationMicroseconds());
    assertEquals(2000000L, event.externalDurationMicroseconds());
    assertEquals(1000000L, event.pendingDurationMicroseconds());

    // v2 Timestamps
    assertEquals(Instant.ofEpochSecond(7), event.transferPendingStartTime());
    assertEquals(Instant.ofEpochSecond(8), event.transferStartTime());
    assertEquals(Instant.ofEpochSecond(9), event.transferEndTime());
    assertEquals(Instant.ofEpochSecond(10), event.transferExternalEndTime());
  }
}
