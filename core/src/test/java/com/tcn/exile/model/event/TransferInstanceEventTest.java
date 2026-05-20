package com.tcn.exile.model.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    var sourceCall = new TransferInstanceEvent.Source.Call(
        123L, CallType.INBOUND, "agent1", "user1", "conv1", 456L, 789L);
    var source = new TransferInstanceEvent.Source(sourceCall);

    var agent = new TransferInstanceEvent.Destination.Agent(1L, "p1", "u1");
    var outbound = new TransferInstanceEvent.Destination.Outbound("12345", 2L, CallType.OUTBOUND, "c2");
    var skills = new TransferInstanceEvent.Destination.Skills(Map.of("skill1", 100L));
    var destination = new TransferInstanceEvent.Destination(agent, outbound, skills);

    var event = new TransferInstanceEvent(
        1L, "org1", "transfer-1",
        source, destination, "type1", "result1", "init1",
        Instant.ofEpochSecond(1), Instant.ofEpochSecond(2), Instant.ofEpochSecond(3),
        Instant.ofEpochSecond(4), Instant.ofEpochSecond(5), Instant.ofEpochSecond(6),
        Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(3),
        true, false,
        3000000L, 2000000L, 1000000L,
        Instant.ofEpochSecond(7), Instant.ofEpochSecond(8), Instant.ofEpochSecond(9), Instant.ofEpochSecond(10)
    );

    assertEquals("transfer-1", event.transferInstanceId());
    assertNotNull(event.source().call());
    assertEquals(123L, event.source().call().callSid());

    assertNotNull(event.destination());
    assertEquals("p1", event.destination().agent().partnerAgentId());
    assertEquals("12345", event.destination().outbound().phoneNumber());
    assertTrue(event.destination().skills().requiredSkills().containsKey("skill1"));

    assertTrue(event.startAsPending());
    assertEquals(3000000L, event.durationMicroseconds());
    assertEquals(Instant.ofEpochSecond(8), event.transferStartTime());
  }
}
