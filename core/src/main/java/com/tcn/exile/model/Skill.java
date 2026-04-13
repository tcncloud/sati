package com.tcn.exile.model;

import java.util.OptionalLong;

public record Skill(String skillId, String name, String description, OptionalLong proficiency) {}
