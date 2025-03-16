package com.tcn.exile.gateclients.v2;

public class BuildVersion {
  public static String getBuildVersion() {
    return BuildVersion.class.getPackage().getImplementationVersion();
  }
}
