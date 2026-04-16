package com.tcn.exile.web.handler;

import com.tcn.exile.ExileClient;
import com.tcn.exile.config.ExileClientManager;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Pure Java handler for NCL ruleset endpoints. No framework dependencies. */
public class NCLRulesetHandler {

  private static final Logger log = LoggerFactory.getLogger(NCLRulesetHandler.class);

  private final ExileClientManager clientManager;

  public NCLRulesetHandler(ExileClientManager clientManager) {
    this.clientManager = clientManager;
  }

  private ExileClient getClient() {
    var client = clientManager.client();
    if (client == null) {
      throw new IllegalStateException("ExileClient is not connected");
    }
    return client;
  }

  public List<String> listNCLRulesetNames() {
    log.debug("listNCLRulesetNames");
    return getClient().calls().listComplianceRulesets();
  }
}
