package com.tcn.exile.plugin;

import io.micronaut.serde.annotation.Serdeable;

import java.util.Map;

@Serdeable
public record PluginStatus(
    String name,
    boolean running,
    int queueMaxSize ,
    long queueCompletedJobs,
    int queueActiveCount,
    Map<String, Object> internalConfig,
    Map<String, Object> internalStatus) {

}
