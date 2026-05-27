package com.tcn.exile.web.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

public class MapFlattenerTest {

  @Test
  void testFlattenNestedMap() {
    Map<String, Object> nestedMap = new HashMap<>();
    nestedMap.put("name", "John");

    Map<String, Object> address = new HashMap<>();
    address.put("street", "123 Main St");
    address.put("city", "Boston");
    nestedMap.put("address", address);

    List<String> phones = new ArrayList<>();
    phones.add("123-456-7890");
    phones.add("098-765-4321");
    nestedMap.put("phones", phones);

    Map<String, Object> flattened = MapFlattener.flatten(nestedMap);

    assertEquals("John", flattened.get("name"));
    assertEquals("123 Main St", flattened.get("address.street"));
    assertEquals("Boston", flattened.get("address.city"));
    assertEquals("123-456-7890", flattened.get("phones[0]"));
    assertEquals("098-765-4321", flattened.get("phones[1]"));
    assertEquals(5, flattened.size());
  }

  @Test
  void testFlattenEmptyMap() {
    Map<String, Object> emptyMap = new HashMap<>();
    Map<String, Object> flattened = MapFlattener.flatten(emptyMap);
    assertTrue(flattened.isEmpty());
  }

  @Test
  void testFlattenWithNestedLists() {
    Map<String, Object> nestedMap = new HashMap<>();

    List<Map<String, Object>> items = new ArrayList<>();
    Map<String, Object> item1 = new HashMap<>();
    item1.put("id", 1);
    item1.put("name", "Item 1");
    items.add(item1);

    Map<String, Object> item2 = new HashMap<>();
    item2.put("id", 2);
    item2.put("name", "Item 2");
    items.add(item2);

    nestedMap.put("items", items);

    Map<String, Object> flattened = MapFlattener.flatten(nestedMap);

    assertEquals(1, flattened.get("items[0].id"));
    assertEquals("Item 1", flattened.get("items[0].name"));
    assertEquals(2, flattened.get("items[1].id"));
    assertEquals("Item 2", flattened.get("items[1].name"));
    assertEquals(4, flattened.size());
  }

  @Test
  void testFlattenWithPrimitiveValues() {
    Map<String, Object> map = new HashMap<>();
    map.put("string", "value");
    map.put("number", 42);
    map.put("boolean", true);
    map.put("null", null);

    Map<String, Object> flattened = MapFlattener.flatten(map);

    assertEquals("value", flattened.get("string"));
    assertEquals(42, flattened.get("number"));
    assertEquals(true, flattened.get("boolean"));
    assertNull(flattened.get("null"));
    assertEquals(4, flattened.size());
  }

  @Test
  void testSearchExactMatch() {
    Map<String, Object> map = new HashMap<>();
    map.put("test.key", "value");
    map.put("other.key", "other");

    assertEquals("value", MapFlattener.search(map, "test.key"));
  }

  @Test
  void testSearchCaseInsensitive() {
    Map<String, Object> map = new HashMap<>();
    map.put("Test.Key", "value");
    map.put("other.key", "other");

    assertEquals("value", MapFlattener.search(map, "test.key"));
  }

  @Test
  void testSearchIgnoreWhitespace() {
    Map<String, Object> map = new HashMap<>();
    map.put("test . key", "value");
    map.put("other.key", "other");

    assertEquals("value", MapFlattener.search(map, "test.key"));
  }

  @Test
  void testSearchPreferNonEmpty() {
    Map<String, Object> map = new HashMap<>();
    map.put("test.key", "");
    map.put("test.key", null);
    map.put("test.key", "value");

    assertEquals("value", MapFlattener.search(map, "test.key"));
  }

  /**
   * Ambiguous matches return null rather than throwing — the caller has no good way to
   * disambiguate, and "no usable result" is the right outcome for a search.
   */
  @Test
  void testSearchMultipleMatchesReturnsNull() {
    Map<String, Object> map = new HashMap<>();
    map.put("test.key1", "value1");
    map.put("test.key2", "value2");

    assertNull(MapFlattener.search(map, "test.key"));
  }

  /**
   * Absent keys return null rather than throwing — historically this threw {@link
   * IllegalArgumentException}, which forced the v3 finvi plugin into a tight nack/redeliver loop
   * whenever a stored-proc result row didn't contain the looked-up field.
   */
  @Test
  void testSearchNoMatchReturnsNull() {
    Map<String, Object> map = new HashMap<>();
    map.put("other.key", "value");

    assertNull(MapFlattener.search(map, "test.key"));
  }

  @Test
  void testSearchSuffixMatch() {
    // The InterSystems IRIS "Expression_1.RPC" pattern: keys carry a prefix from the SELECT
    // expression and the suffix is the real field name.
    Map<String, Object> map = new HashMap<>();
    map.put("Expression_1.callLogId", "1881");
    map.put("Expression_1.callSid", 19109);
    map.put("Expression_1.RPC", 1);

    assertEquals(1, MapFlattener.search(map, "RPC"));
    assertEquals(19109, MapFlattener.search(map, "callSid"));
    assertEquals("1881", MapFlattener.search(map, "callLogId"));
  }

  @Test
  void testSearchSuffixMultipleMatchesReturnsNull() {
    // Multiple suffix matches with no unique non-empty value → null.
    Map<String, Object> map = new HashMap<>();
    map.put("field1.name", "value1");
    map.put("field2.name", "value2");

    assertNull(MapFlattener.search(map, "name"));
  }
}
