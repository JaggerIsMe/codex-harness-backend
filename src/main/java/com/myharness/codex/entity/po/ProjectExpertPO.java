package com.myharness.codex.entity.po;

public class ProjectExpertPO {
    private Long projectId;
    private Long expertId;
    private Long expertVersionId;
    private String name;
    private String description;
    private Long versionNo;
    private Long latestVersionId;
    private Long latestVersionNo;
    private String status;
    public Long getProjectId() { return projectId; }
    public void setProjectId(Long value) { projectId = value; }
    public Long getExpertId() { return expertId; }
    public void setExpertId(Long value) { expertId = value; }
    public Long getExpertVersionId() { return expertVersionId; }
    public void setExpertVersionId(Long value) { expertVersionId = value; }
    public String getName() { return name; }
    public void setName(String value) { name = value; }
    public String getDescription() { return description; }
    public void setDescription(String value) { description = value; }
    public Long getVersionNo() { return versionNo; }
    public void setVersionNo(Long value) { versionNo = value; }
    public Long getLatestVersionId() { return latestVersionId; }
    public void setLatestVersionId(Long value) { latestVersionId = value; }
    public Long getLatestVersionNo() { return latestVersionNo; }
    public void setLatestVersionNo(Long value) { latestVersionNo = value; }
    public String getStatus() { return status; }
    public void setStatus(String value) { status = value; }
}
