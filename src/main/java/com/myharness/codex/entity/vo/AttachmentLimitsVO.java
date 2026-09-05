package com.myharness.codex.entity.vo;
public record AttachmentLimitsVO(long maxFileBytes, int maxFiles, long maxTotalBytes, boolean agentSupported) {}
