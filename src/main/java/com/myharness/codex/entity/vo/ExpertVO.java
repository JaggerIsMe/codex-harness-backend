package com.myharness.codex.entity.vo;

public record ExpertVO(Long id, String name, String description, String status, Long publishedVersionId, Long revision,
                       String systemPrompt, java.util.List<Long> skillVersionIds, java.util.List<Long> mcpBindings) {}

