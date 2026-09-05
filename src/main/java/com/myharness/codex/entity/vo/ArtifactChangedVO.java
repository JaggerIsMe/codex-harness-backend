package com.myharness.codex.entity.vo;

public record ArtifactChangedVO(String type, Long deviceId, Payload payload) {
    public record Payload(String conversationId, String turnId) {}
}
