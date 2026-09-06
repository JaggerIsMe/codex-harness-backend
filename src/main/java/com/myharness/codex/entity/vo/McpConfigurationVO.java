package com.myharness.codex.entity.vo;

import com.myharness.codex.entity.dto.McpRuntimeSpecDTO;
import java.time.LocalDateTime;

public record McpConfigurationVO(Long id,String serverCode,String name,String description,String status,
                                 Long currentVersionId,Long currentVersionNo,String configDigest,Long revision,
                                 McpRuntimeSpecDTO runtimeSpec,LocalDateTime createdAt,LocalDateTime updatedAt) {}
