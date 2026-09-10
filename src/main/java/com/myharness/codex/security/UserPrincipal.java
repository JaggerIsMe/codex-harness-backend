package com.myharness.codex.security;

public class UserPrincipal {

    private final Long id;
    private final String email;
    private final String displayName;

    public UserPrincipal(Long id, String email, String displayName) {
        this.id = id;
        this.email = email;
        this.displayName = displayName;
    }

    public Long getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public String getDisplayName() {
        return displayName;
    }
}
