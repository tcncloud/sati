package com.tcn.sati.util;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Flattens nested maps into single-level maps with dot-notation keys.
 * Nested maps become "parent.child", lists become "parent[0]", "parent[1]", etc.
 *
 * Example: {"address": {"street": "123 Main"}} → {"address.street": "123 Main"}
 */
public class MapFlattener {

    public static Map<String, Object> flatten(Map<String, Object> input) {
        Map<String, Object> result = new HashMap<>();
        flattenMap("", input, result);
        return result;
    }

    private static void flattenMap(String prefix, Map<String, Object> input, Map<String, Object> result) {
        for (Map.Entry<String, Object> entry : input.entrySet()) {
            String key = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
            Object value = entry.getValue();

            if (value instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> nested = (Map<String, Object>) value;
                flattenMap(key, nested, result);
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
                Map<String, Object> nested = (Map<String, Object>) value;
                flattenMap(key, nested, result);
            } else if (value instanceof List) {
                flattenList(key, (List<?>) value, result);
            } else {
                result.put(key, value);
            }
        }
    }
}
