package com.myharness.codex.entity.vo;
public record WorkspaceFileCapabilitiesVO(Capability mutations,Capability archive) {
    public record Capability(boolean supported,boolean enabled,String reason) {}
}
