package com.myharness.codex.entity.vo;
public record WorkspaceFilesChangedVO(String type, Long deviceId, Payload payload) {
    public record Payload(String projectId, String path, String operationId) {}
}
