package com.tcn.exile.web.util;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Utility class to flatten nested maps into a single-level map with primitive values. Handles
 * nested maps, lists, and primitive types.
 */
public class MapFlattener {
  private static final Logger log = LoggerFactory.getLogger(MapFlattener.class);

  public static Map<String, Object> flatten(Map<String, Object> input) {
    Map<String, Object> result = new HashMap<>();
    flattenMap("", input, result);
    return result;
  }

  /**
   * Searches a flattened map for a value matching the given suffix. Search logic:
   *
   * <ol>
   *   <li>Exact match (case-sensitive)
   *   <li>Case-insensitive match ignoring whitespace
   *   <li>If multiple matches found, prefer non-empty values
   *   <li>If no good match found, return {@code null}
   * </ol>
   *
   * <p>"No match" and "ambiguous match" are returned as {@code null} rather than thrown so callers
   * can treat absence as a normal outcome — most lookups for optional fields don't want to wrap
   * every call in try/catch. A previous version threw {@link IllegalArgumentException}; that forced
   * the v3 finvi plugin into a tight nack/redeliver loop whenever a stored-proc result row lacked
   * an RPC field, which is a normal outcome rather than an error.
   */
  public static Object search(Map<String, Object> flattenedMap, String suffix) {
    if (flattenedMap.containsKey(suffix)) {
      return flattenedMap.get(suffix);
    }

    String normalizedSuffix = normalizeKey(suffix);
    List<Map.Entry<String, Object>> matches =
        flattenedMap.entrySet().stream()
            .filter(entry -> normalizeKey(entry.getKey()).endsWith(normalizedSuffix))
            .collect(Collectors.toList());

    if (matches.size() == 1) {
      return matches.get(0).getValue();
    }

    if (!matches.isEmpty()) {
      List<Map.Entry<String, Object>> nonEmptyMatches =
          matches.stream()
              .filter(entry -> !isEmptyValue(entry.getValue()))
              .collect(Collectors.toList());

      if (nonEmptyMatches.size() == 1) {
        return nonEmptyMatches.get(0).getValue();
      }
    }

    log.debug("No good match found for suffix '{}'. Found {} matches", suffix, matches.size());
    return null;
  }

  private static String normalizeKey(String key) {
    return key.toLowerCase().replaceAll("\\s+", "");
  }

  private static boolean isEmptyValue(Object value) {
    if (value == null) return true;
    if (value instanceof String) return ((String) value).trim().isEmpty();
    return false;
  }

  private static void flattenMap(
      String prefix, Map<String, Object> input, Map<String, Object> result) {
    for (Map.Entry<String, Object> entry : input.entrySet()) {
      String key = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
      Object value = entry.getValue();

      if (value instanceof Map) {
        @SuppressWarnings("unchecked")
        Map<String, Object> nestedMap = (Map<String, Object>) value;
        flattenMap(key, nestedMap, result);
      } else if (value instanceof List) {
        flattenList(key, (List<?>) value, result);
      } else {
        result.put(key, value);
      }
    }
  }

  private static void flattenList(String prefix, List<?> list, Map<String, Object> result) {
    for (int i = 0; i < list.size(); i++) {
      String key = prefix + "[" + i + "]";
      Object value = list.get(i);

      if (value instanceof Map) {
        @SuppressWarnings("unchecked")
        Map<String, Object> nestedMap = (Map<String, Object>) value;
        flattenMap(key, nestedMap, result);
      } else if (value instanceof List) {
        flattenList(key, (List<?>) value, result);
      } else {
        result.put(key, value);
      }
    }
  }
}
