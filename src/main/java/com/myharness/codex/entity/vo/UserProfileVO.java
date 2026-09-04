package com.myharness.codex.entity.vo;

public class UserProfileVO {

    private final Long id;
    private final String username;
    private final String displayName;
    private java.util.List<String> roles=java.util.List.of();
    private java.util.List<String> permissions=java.util.List.of();
    private boolean mustChangePassword;
    public java.util.List<String> getRoles() { return roles; }
    public java.util.List<String> getPermissions() { return permissions; }
    public boolean isMustChangePassword() { return mustChangePassword; }
    public UserProfileVO withAccess(java.util.List<String> roles,java.util.List<String> permissions,boolean mustChange) {
        this.roles=java.util.List.copyOf(roles); this.permissions=java.util.List.copyOf(permissions);
        this.mustChangePassword=mustChange; return this;
    }

    public UserProfileVO(Long id, String username, String displayName) {
        this.id = id;
        this.username = username;
        this.displayName = displayName;
    }

    public Long getId() {
        return id;
    }

    public String getUsername() {
        return username;
    }

    public String getDisplayName() {
        return displayName;
    }
}
