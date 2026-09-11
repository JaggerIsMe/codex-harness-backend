package com.myharness.codex.entity.vo;

public record SessionActivityVO(String sessionId, long idleExpiresAt, long sessionExpiresAt) {}
