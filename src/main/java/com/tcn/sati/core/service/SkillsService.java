package com.tcn.sati.core.service;

import com.tcn.sati.core.service.dto.SkillDto.AssignSkillRequest;
import com.tcn.sati.core.service.dto.SkillDto.SkillInfo;
import com.tcn.sati.core.service.dto.SkillDto.UnassignSkillRequest;
import com.tcn.sati.core.service.dto.SuccessResult;
import com.tcn.sati.infra.gate.GateClient;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Skills service. Subclass to override behavior.
 */
public class SkillsService {
        protected final GateClient gate;

        public SkillsService(GateClient gate) {
                this.gate = gate;
        }

        public List<SkillInfo> listSkills() {
                var resp = gate.listSkills(
                                build.buf.gen.tcnapi.exile.gate.v2.ListSkillsRequest.newBuilder().build());
                return resp.getSkillsList().stream()
                                .map(this::toSkillInfo)
                                .collect(Collectors.toList());
        }

        public List<SkillInfo> listAgentSkills(String agentId) {
                var resp = gate.listAgentSkills(
                                build.buf.gen.tcnapi.exile.gate.v2.ListAgentSkillsRequest.newBuilder()
                                                .setPartnerAgentId(agentId).build());
                return resp.getSkillsList().stream()
                                .map(this::toSkillInfo)
                                .collect(Collectors.toList());
        }

        public SuccessResult assignSkill(AssignSkillRequest request) {
                gate.assignAgentSkill(
                                build.buf.gen.tcnapi.exile.gate.v2.AssignAgentSkillRequest.newBuilder()
                                                .setPartnerAgentId(request.partnerAgentId)
                                                .setSkillId(request.skillId)
                                                .setProficiency(request.proficiency)
                                                .build());
                return new SuccessResult();
        }

        public SuccessResult unassignSkill(UnassignSkillRequest request) {
                gate.unassignAgentSkill(
                                build.buf.gen.tcnapi.exile.gate.v2.UnassignAgentSkillRequest.newBuilder()
                                                .setPartnerAgentId(request.partnerAgentId)
                                                .setSkillId(request.skillId)
                                                .build());
                return new SuccessResult();
        }

        protected SkillInfo toSkillInfo(build.buf.gen.tcnapi.exile.gate.v2.Skill s) {
                var info = new SkillInfo();
                info.skillId = s.getSkillId();
                info.name = s.getName();
                info.description = s.getDescription();
                info.proficiency = s.getProficiency();
                return info;
        }
}
