package com.tcn.exile.demo;

import com.google.protobuf.Descriptors.*;
//import com.google.protobuf.Descriptors.FileDescriptor;
import com.tcn.exile.gateclients.v2.BuildVersion;
import com.tcn.exile.plugin.PluginInterface;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.serde.annotation.SerdeImport;
import jakarta.inject.Inject;
import tcnapi.exile.gate.v2.Public;

@Controller("/version")
public class VersionController {
  @Inject
  PluginInterface plugin;

  @Get
  public VersionInfo index() {
    var ver = plugin.info();
    return new VersionInfo(
        ver.getCoreVersion(),
        ver.getServerName(),
        ver.getPluginVersion(),
        ver.getPluginName()
    );

  }
}
