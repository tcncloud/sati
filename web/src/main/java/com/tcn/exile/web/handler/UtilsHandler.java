package com.tcn.exile.web.handler;

/** Pure Java handler for utility endpoints. No framework dependencies. */
public class UtilsHandler {

  private final Class<?> pluginClass;

  /**
   * @param pluginClass the main plugin class whose package provides the implementation version
   */
  public UtilsHandler(Class<?> pluginClass) {
    this.pluginClass = pluginClass;
  }

  public String getVersion() {
    if (pluginClass.getPackage().getImplementationVersion() == null) {
      return "Unknown - not packaged";
    }
    return pluginClass.getPackage().getImplementationVersion();
  }

  public String getCoreVersion() {
    try {
      var pkg = com.tcn.exile.ExileClient.class.getPackage();
      if (pkg != null && pkg.getImplementationVersion() != null) {
        return pkg.getImplementationVersion();
      }
    } catch (Exception e) {
      // ignore
    }
    return "unknown";
  }
}
