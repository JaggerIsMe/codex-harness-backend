package com.myharness.codex.entity.vo;

public record ExpertSelectionVO(Long expertId, Long expertVersionId, String name, Long selectionRevision, Long projectRevision, boolean available, String unavailableReason) {}

