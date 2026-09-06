package com.myharness.codex.entity.vo;

import com.myharness.codex.entity.dto.ModelRuntimeSpecDTO;
import java.time.LocalDateTime;

public record ModelConfigurationVO(Long id,String configurationCode,String name,String description,String status,
    Long currentVersionId,Long currentVersionNo,String configDigest,Long revision,ModelRuntimeSpecDTO runtime,
    String apiKeyMasked,LocalDateTime createdAt,LocalDateTime updatedAt) {}
