package com.tcn.sati.core.service;

import com.tcn.sati.infra.gate.GateClient;

/**
 * NCL Ruleset service. Subclass to override behavior.
 */
public class NCLRulesetService {
    protected final GateClient gate;

    public NCLRulesetService(GateClient gate) {
        this.gate = gate;
    }

    public Object listNames() {
        var resp = gate.listComplianceRulesets(
                build.buf.gen.tcnapi.exile.gate.v3.ListComplianceRulesetsRequest.newBuilder().build());
        return resp.getRulesetNamesList();
    }
}
