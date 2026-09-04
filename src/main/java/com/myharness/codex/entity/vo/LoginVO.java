package com.myharness.codex.entity.vo;

public class LoginVO {

    private final String accessToken;
    private final String tokenType;
    private final long expiresInSeconds;
    private final UserProfileVO user;

    public LoginVO(String accessToken, long expiresInSeconds, UserProfileVO user) {
        this.accessToken = accessToken;
        this.tokenType = "Bearer";
        this.expiresInSeconds = expiresInSeconds;
        this.user = user;
    }

    public String getAccessToken() {
        return accessToken;
    }

    public String getTokenType() {
        return tokenType;
    }

    public long getExpiresInSeconds() {
        return expiresInSeconds;
    }

    public UserProfileVO getUser() {
        return user;
    }
}
