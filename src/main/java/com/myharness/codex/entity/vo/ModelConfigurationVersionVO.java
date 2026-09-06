package com.myharness.codex.entity.vo;

import com.myharness.codex.entity.dto.ModelRuntimeSpecDTO;
import java.time.LocalDateTime;

public record ModelConfigurationVersionVO(Long id,Long modelConfigurationId,Long versionNo,String configurationCode,
    String name,String description,String status,String configDigest,ModelRuntimeSpecDTO runtime,String apiKeyMasked,
    LocalDateTime createdAt) {}
