package com.myharness.codex.entity.vo;

public record ExpertSkillUpdateVO(Long skillId, String skillName, Long currentVersionId, String currentVersion,
                                 Long availableVersionId, String availableVersion) {}
