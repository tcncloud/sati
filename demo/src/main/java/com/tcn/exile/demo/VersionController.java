package com.tcn.exile.demo;

import com.tcn.exile.gateclients.v2.BuildVersion;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;

@Controller("/version")
public class VersionController {

    @Get
    public String index() {
        return BuildVersion.getBuildVersion();
    }
}
