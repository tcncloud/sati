package com.tcn.sati.core.service.dto;

import io.javalin.openapi.OpenApiByFields;

/**
 * DTOs for SkillsService responses.

 */
public class SkillDto {

    /** Skill info — returned by listSkills and listAgentSkills. */
    @OpenApiByFields
    public static class SkillInfo {
        public String skillId;
        public String name;
        public String description;
        public Long proficiency;
    }

    // ========== Request DTOs ==========

    /** Request for assigning a skill to an agent. */
    @OpenApiByFields
    public static class AssignSkillRequest {
        public String skillId;
        public Long proficiency;
    }

    /** Request for unassigning a skill from an agent. */
    @OpenApiByFields
    public static class UnassignSkillRequest {
        public String skillId;
    }
}
