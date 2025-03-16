package com.tcn.exile.demo;

import io.micronaut.serde.annotation.Serdeable;

@Serdeable
public record  VersionInfo(String coreVersion, String serverName, String pluginVersion, String pluginName) {
}
