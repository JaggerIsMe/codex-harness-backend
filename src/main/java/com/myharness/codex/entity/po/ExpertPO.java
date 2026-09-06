package com.myharness.codex.entity.po;

public class ExpertPO {
    private Long id;
    private String name;
    private String description;
    private String systemPrompt;
    private String skillVersionIds;
    private String mcpVersionIds;
    private String status;
    private Long publishedVersionId;
    private Long revision;
    private Long createdBy;
    public Long getId() { return id; }
    public void setId(Long value) { id = value; }
    public String getName() { return name; }
    public void setName(String value) { name = value; }
    public String getDescription() { return description; }
    public void setDescription(String value) { description = value; }
    public String getSystemPrompt() { return systemPrompt; }
    public void setSystemPrompt(String value) { systemPrompt = value; }
    public String getSkillVersionIds() { return skillVersionIds; }
    public void setSkillVersionIds(String value) { skillVersionIds = value; }
    public String getMcpVersionIds() { return mcpVersionIds; }
    public void setMcpVersionIds(String value) { mcpVersionIds = value; }
    public String getStatus() { return status; }
    public void setStatus(String value) { status = value; }
    public Long getPublishedVersionId() { return publishedVersionId; }
    public void setPublishedVersionId(Long value) { publishedVersionId = value; }
    public Long getRevision() { return revision; }
    public void setRevision(Long value) { revision = value; }
    public Long getCreatedBy() { return createdBy; }
    public void setCreatedBy(Long value) { createdBy = value; }
}

