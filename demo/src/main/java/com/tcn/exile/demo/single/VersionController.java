package com.tcn.exile.demo.single;

import com.tcn.exile.plugin.PluginInterface;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import jakarta.inject.Inject;

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