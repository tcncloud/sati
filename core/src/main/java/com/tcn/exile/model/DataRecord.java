package com.tcn.exile.model;

import java.util.Map;

public record DataRecord(String poolId, String recordId, Map<String, Object> payload) {}
