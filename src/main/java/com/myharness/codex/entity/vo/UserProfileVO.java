package com.myharness.codex.entity.vo;

public class UserProfileVO {

    private final Long id;
    private final String username;
    private final String displayName;

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
