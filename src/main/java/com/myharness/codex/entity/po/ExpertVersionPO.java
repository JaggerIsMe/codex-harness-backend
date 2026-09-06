package com.myharness.codex.entity.po;

public class ExpertVersionPO {
    private Long id;
    private Long expertId;
    private Long versionNo;
    private String name;
    private String description;
    private String systemPrompt;
    private String skillVersionIds;
    private Boolean compatibleUpgrade;
    private String status;
    public Long getId() { return id; }
    public void setId(Long value) { id = value; }
    public Long getExpertId() { return expertId; }
    public void setExpertId(Long value) { expertId = value; }
    public Long getVersionNo() { return versionNo; }
    public void setVersionNo(Long value) { versionNo = value; }
    public String getName() { return name; }
    public void setName(String value) { name = value; }
    public String getDescription() { return description; }
    public void setDescription(String value) { description = value; }
    public String getSystemPrompt() { return systemPrompt; }
    public void setSystemPrompt(String value) { systemPrompt = value; }
    public String getSkillVersionIds() { return skillVersionIds; }
    public void setSkillVersionIds(String value) { skillVersionIds = value; }
    public Boolean getCompatibleUpgrade() { return compatibleUpgrade; }
    public void setCompatibleUpgrade(Boolean value) { compatibleUpgrade = value; }
    public String getStatus() { return status; }
    public void setStatus(String value) { status = value; }
}
