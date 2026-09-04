package com.myharness.codex.entity.vo;

import com.myharness.codex.entity.po.SkillPO;
import java.time.LocalDateTime;
import java.util.List;

public class SkillVO {
    private final Long id;
    private final String skillName;
    private final String description;
    private final String status;
    private final Integer versionCount;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;
    private final List<SkillVersionVO> versions;

    public SkillVO(SkillPO value, List<SkillVersionVO> versions) {
        id = value.getId(); skillName = value.getSkillName(); description = value.getDescription(); status = value.getStatus();
        versionCount = value.getVersionCount(); createdAt = value.getCreatedAt(); updatedAt = value.getUpdatedAt(); this.versions = versions;
    }
    public Long getId() { return id; }
    public String getSkillName() { return skillName; }
    public String getDescription() { return description; }
    public String getStatus() { return status; }
    public Integer getVersionCount() { return versionCount; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public List<SkillVersionVO> getVersions() { return versions; }
}
