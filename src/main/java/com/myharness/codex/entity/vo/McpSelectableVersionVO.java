package com.myharness.codex.entity.vo;

public record McpSelectableVersionVO(Long configurationId,Long versionId,Long versionNo,String serverCode,
                                     String name,String transportType,String configDigest,java.util.List<Long> previousVersionIds) {}
