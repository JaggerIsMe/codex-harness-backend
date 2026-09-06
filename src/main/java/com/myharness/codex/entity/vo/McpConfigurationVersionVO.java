package com.myharness.codex.entity.vo;

import com.myharness.codex.entity.dto.McpRuntimeSpecDTO;
import java.time.LocalDateTime;

public record McpConfigurationVersionVO(Long id,Long configurationId,Long versionNo,String serverCode,String name,
                                        String description,String status,String configDigest,McpRuntimeSpecDTO runtimeSpec,
                                        LocalDateTime createdAt) {}
