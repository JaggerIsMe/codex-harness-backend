package com.myharness.codex.entity.dto;

public class ExpertDraftDTO {
    private @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max=128) String name;
    private @jakarta.validation.constraints.Size(max=2000) String description;
    private @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max=30000) String systemPrompt;
    private @jakarta.validation.constraints.NotNull @jakarta.validation.constraints.Size(max=30) java.util.List<@jakarta.validation.constraints.Positive Long> skillVersionIds = java.util.List.of();
    private @jakarta.validation.constraints.NotNull @jakarta.validation.constraints.Size(max=20) java.util.List<@jakarta.validation.constraints.Positive Long> mcpBindings = java.util.List.of();
    private @jakarta.validation.constraints.NotNull @jakarta.validation.constraints.Size(max=0) java.util.List<String> knowledgeBindings = java.util.List.of();
    private @jakarta.validation.constraints.PositiveOrZero Long revision;
    public String getName() { return name; }
    public void setName(String value) { name = value; }
    public String getDescription() { return description; }
    public void setDescription(String value) { description = value; }
    public String getSystemPrompt() { return systemPrompt; }
    public void setSystemPrompt(String value) { systemPrompt = value; }
    public java.util.List<Long> getSkillVersionIds() { return skillVersionIds; }
    public void setSkillVersionIds(java.util.List<Long> value) { skillVersionIds = value; }
    public java.util.List<Long> getMcpBindings() { return mcpBindings; }
    public void setMcpBindings(java.util.List<Long> value) { mcpBindings = value; }
    public java.util.List<String> getKnowledgeBindings() { return knowledgeBindings; }
    public void setKnowledgeBindings(java.util.List<String> value) { knowledgeBindings = value; }
    public Long getRevision() { return revision; }
    public void setRevision(Long value) { revision = value; }
}

