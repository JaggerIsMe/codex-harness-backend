package com.myharness.codex.entity.po;

public class SkillDownloadPO {
    private Long versionId;
    private Long deviceSkillId;
    private String storagePath;
    private String sha256;
    private Long fileSize;
    private String versionStatus;
    private String installStatus;

    public Long getVersionId() { return versionId; }
    public void setVersionId(Long value) { versionId=value; }
    public Long getDeviceSkillId() { return deviceSkillId; }
    public void setDeviceSkillId(Long value) { deviceSkillId=value; }
    public String getStoragePath() { return storagePath; }
    public void setStoragePath(String value) { storagePath=value; }
    public String getSha256() { return sha256; }
    public void setSha256(String value) { sha256=value; }
    public Long getFileSize() { return fileSize; }
    public void setFileSize(Long value) { fileSize=value; }
    public String getVersionStatus() { return versionStatus; }
    public void setVersionStatus(String value) { versionStatus=value; }
    public String getInstallStatus() { return installStatus; }
    public void setInstallStatus(String value) { installStatus=value; }
}
