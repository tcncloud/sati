package com.tcn.exile.model;

import java.util.Map;

public record Record(String poolId, String recordId, Map<String, Object> payload) {}
