package com.myharness.codex.security;

public class UserPrincipal {

    private final Long id;
    private final String username;
    private final String displayName;

    public UserPrincipal(Long id, String username, String displayName) {
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
