package com.myharness.codex.entity.vo;

import com.myharness.codex.entity.po.DeviceModelAssignmentPO;
import java.time.LocalDateTime;

public record DeviceModelAssignmentVO(Long deviceId,Long modelConfigurationVersionId,Long revision,
    Long configurationId,Long versionNo,String configurationCode,String name,String modelId,LocalDateTime updatedAt) {
    public DeviceModelAssignmentVO(DeviceModelAssignmentPO v){this(v.getDeviceId(),v.getModelConfigurationVersionId(),v.getRevision(),
        v.getConfigurationId(),v.getVersionNo(),v.getConfigurationCode(),v.getName(),v.getModelId(),v.getUpdatedAt());}
}
