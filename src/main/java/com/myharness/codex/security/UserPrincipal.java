package com.myharness.codex.security;

public class UserPrincipal {

    private final Long id;
    private final String email;
    private final String displayName;
    private final RedisLoginSessionStore.Session loginSession;

    public UserPrincipal(Long id, String email, String displayName) {
        this(id, email, displayName, null);
    }

    public UserPrincipal(Long id, String email, String displayName, RedisLoginSessionStore.Session loginSession) {
        this.id = id;
        this.email = email;
        this.displayName = displayName;
        this.loginSession = loginSession;
    }

    public RedisLoginSessionStore.Session getLoginSession() { return loginSession; }

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
