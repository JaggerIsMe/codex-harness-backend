package com.myharness.codex.entity.vo;

public record ProjectExpertVO(Long expertId, Long expertVersionId, Long versionNo,
                              Long latestVersionId, Long latestVersionNo, boolean upgradeAvailable,
                              String name, String description, boolean available, String unavailableReason) {}
