package com.myharness.codex.entity.vo;

public class LoginVO extends SessionTokenVO {

    private final UserProfileVO user;

    public LoginVO(String accessToken, long expiresInSeconds, UserProfileVO user) {
        super(accessToken,expiresInSeconds,0,null,0,0,600,0,null);
        this.user = user;
    }

    public LoginVO(SessionTokenVO credentials,UserProfileVO user) {
        super(credentials.getAccessToken(),credentials.getExpiresInSeconds(),credentials.getExpiresAt(),
                credentials.getSessionId(),credentials.getSessionExpiresAt(),credentials.getIdleExpiresAt(),
                credentials.getRefreshBeforeSeconds(),credentials.getCredentialGeneration(),credentials.getRefreshCookieValue());
        this.user=user;
    }

    public UserProfileVO getUser() {
        return user;
    }
}
