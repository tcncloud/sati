package com.tcn.exile.model;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ModelTest {

  @Test
  void pageHasMore() {
    var page = new Page<>(List.of("a", "b"), "next-token");
    assertTrue(page.hasMore());
    assertEquals(2, page.items().size());
  }

  @Test
  void pageNoMore() {
    var page = new Page<>(List.of("a"), "");
    assertFalse(page.hasMore());
  }

  @Test
  void pageNullToken() {
    var page = new Page<>(List.of("a"), null);
    assertFalse(page.hasMore());
  }

  @Test
  void dataRecordEquality() {
    var r1 = new DataRecord("P-1", "R-1", Map.of("k", "v"));
    var r2 = new DataRecord("P-1", "R-1", Map.of("k", "v"));
    assertEquals(r1, r2);
  }

  @Test
  void poolEquality() {
    var p1 = new Pool("P-1", "desc", Pool.PoolStatus.READY, 10);
    var p2 = new Pool("P-1", "desc", Pool.PoolStatus.READY, 10);
    assertEquals(p1, p2);
    assertNotEquals(p1, new Pool("P-2", "desc", Pool.PoolStatus.READY, 10));
  }

  @Test
  void filterOperatorValues() {
    assertEquals(8, Filter.Operator.values().length);
  }

  @Test
  void callTypeValues() {
    assertEquals(6, CallType.values().length);
  }

  @Test
  void agentStateValues() {
    assertEquals(19, AgentState.values().length);
  }

  @Test
  void taskDataRecord() {
    var td = new TaskData("key", "value");
    assertEquals("key", td.key());
    assertEquals("value", td.value());
  }
}
