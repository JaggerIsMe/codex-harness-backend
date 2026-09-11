package com.myharness.codex.entity.vo;

public record ExpertMcpUpdateVO(Long configurationId, String name, Long currentVersionId, Long currentVersionNo,
                                Long availableVersionId, Long availableVersionNo) {}
