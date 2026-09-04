package com.myharness.codex.entity.vo;

import com.myharness.codex.entity.po.SkillVersionPO;
import java.time.LocalDateTime;

public class SkillVersionVO {
    private final Long id;
    private final Long skillId;
    private final String version;
    private final String sha256;
    private final Long fileSize;
    private final String status;
    private final LocalDateTime createdAt;

    public SkillVersionVO(SkillVersionPO value) {
        id = value.getId(); skillId = value.getSkillId(); version = value.getVersion(); sha256 = value.getSha256();
        fileSize = value.getFileSize(); status = value.getStatus(); createdAt = value.getCreatedAt();
    }
    public Long getId() { return id; }
    public Long getSkillId() { return skillId; }
    public String getVersion() { return version; }
    public String getSha256() { return sha256; }
    public Long getFileSize() { return fileSize; }
    public String getStatus() { return status; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
