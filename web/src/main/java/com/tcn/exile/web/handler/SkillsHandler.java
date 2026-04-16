package com.tcn.exile.web.handler;

import com.tcn.exile.ExileClient;
import com.tcn.exile.config.ExileClientManager;
import com.tcn.exile.web.dto.SkillDto;
import java.util.List;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Pure Java handler for skill management endpoints. No framework dependencies. */
public class SkillsHandler {

  private static final Logger log = LoggerFactory.getLogger(SkillsHandler.class);

  private final ExileClientManager clientManager;

  public SkillsHandler(ExileClientManager clientManager) {
    this.clientManager = clientManager;
  }

  private ExileClient getClient() {
    var client = clientManager.client();
    if (client == null) {
      throw new IllegalStateException("ExileClient is not connected");
    }
    return client;
  }

  public List<SkillDto> listSkills() {
    log.debug("listSkills");
    return getClient().agents().listSkills().stream()
        .map(
            skill ->
                new SkillDto(
                    skill.skillId(),
                    skill.name(),
                    skill.description(),
                    skill.proficiency().isPresent() ? skill.proficiency().getAsLong() : null))
        .collect(Collectors.toList());
  }

  public List<SkillDto> listAgentSkills(String partnerAgentId) {
    log.debug("listAgentSkills for agent {}", partnerAgentId);
    return getClient().agents().listAgentSkills(partnerAgentId).stream()
        .map(
            skill ->
                new SkillDto(
                    skill.skillId(),
                    skill.name(),
                    skill.description(),
                    skill.proficiency().isPresent() ? skill.proficiency().getAsLong() : null))
        .collect(Collectors.toList());
  }

  public void assignAgentSkill(String partnerAgentId, String skillId, Long proficiency) {
    log.debug(
        "assignSkill for agent {}: skillId={}, proficiency={}",
        partnerAgentId,
        skillId,
        proficiency);

    if (partnerAgentId == null || partnerAgentId.isBlank()) {
      throw new IllegalArgumentException("partnerAgentId is required");
    }
    if (skillId == null || skillId.isBlank()) {
      throw new IllegalArgumentException("skillId is required to assign a skill");
    }

    getClient()
        .agents()
        .assignAgentSkill(partnerAgentId, skillId, proficiency != null ? proficiency : 0L);
  }

  public void unassignAgentSkill(String partnerAgentId, String skillId) {
    log.debug("unassignSkill for agent {}: skillId={}", partnerAgentId, skillId);

    if (partnerAgentId == null || partnerAgentId.isBlank()) {
      throw new IllegalArgumentException("partnerAgentId is required");
    }
    if (skillId == null || skillId.isBlank()) {
      throw new IllegalArgumentException("skillId is required to unassign a skill");
    }

    getClient().agents().unassignAgentSkill(partnerAgentId, skillId);
  }
}
