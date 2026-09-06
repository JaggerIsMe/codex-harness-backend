package com.myharness.codex.entity.vo;

public record ExpertVersionVO(Long id, Long expertId, Long versionNo, String name, String description,
                              java.util.List<Long> skillVersionIds, java.util.List<Long> mcpBindings,
                              boolean compatibleUpgrade) {}
