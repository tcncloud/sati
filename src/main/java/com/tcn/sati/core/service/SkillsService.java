package com.tcn.sati.core.service;

import com.tcn.sati.infra.gate.GateClient;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Skills service. Subclass to override behavior.
 */
public class SkillsService {
        protected final GateClient gate;

        public SkillsService(GateClient gate) {
                this.gate = gate;
        }

        public Object listSkills() {
                var resp = gate.listSkills(
                                build.buf.gen.tcnapi.exile.gate.v2.ListSkillsRequest.newBuilder().build());
                return resp.getSkillsList().stream()
                                .map(s -> {
                                        var map = new HashMap<String, Object>();
                                        map.put("skillId", s.getSkillId());
                                        map.put("name", s.getName());
                                        map.put("description", s.getDescription());
                                        map.put("proficiency", s.getProficiency());
                                        return (Map<String, Object>) map;
                                })
                                .collect(Collectors.toList());
        }

        public Object listAgentSkills(String agentId) {
                var resp = gate.listAgentSkills(
                                build.buf.gen.tcnapi.exile.gate.v2.ListAgentSkillsRequest.newBuilder()
                                                .setPartnerAgentId(agentId).build());
                return resp.getSkillsList().stream()
                                .map(s -> {
                                        var map = new HashMap<String, Object>();
                                        map.put("skillId", s.getSkillId());
                                        map.put("name", s.getName());
                                        map.put("description", s.getDescription());
                                        map.put("proficiency", s.getProficiency());
                                        return (Map<String, Object>) map;
                                })
                                .collect(Collectors.toList());
        }

        public Map<String, Object> assignSkill(String agentId, String skillId, int proficiency) {
                gate.assignAgentSkill(
                                build.buf.gen.tcnapi.exile.gate.v2.AssignAgentSkillRequest.newBuilder()
                                                .setPartnerAgentId(agentId)
                                                .setSkillId(skillId)
                                                .setProficiency(proficiency)
                                                .build());
                return Map.of("success", true);
        }

        public Map<String, Object> unassignSkill(String agentId, String skillId) {
                gate.unassignAgentSkill(
                                build.buf.gen.tcnapi.exile.gate.v2.UnassignAgentSkillRequest.newBuilder()
                                                .setPartnerAgentId(agentId)
                                                .setSkillId(skillId)
                                                .build());
                return Map.of("success", true);
        }
}
