package com.myharness.codex.entity.vo;

import com.fasterxml.jackson.annotation.JsonIgnore;

/** The rotating refresh secret is transported only through HttpOnly cookies. */
public class SessionTokenVO {
    private final String accessToken;
    private final long expiresInSeconds;
    private final long expiresAt;
    private final String sessionId;
    private final long sessionExpiresAt;
    private final long idleExpiresAt;
    private final long refreshBeforeSeconds;
    private final long credentialGeneration;
    @JsonIgnore private final String refreshCookieValue;

    public SessionTokenVO(String accessToken, long expiresInSeconds, long expiresAt, String sessionId,
                          long sessionExpiresAt, long idleExpiresAt, long refreshBeforeSeconds,
                          long credentialGeneration, String refreshCookieValue) {
        this.accessToken=accessToken; this.expiresInSeconds=expiresInSeconds; this.expiresAt=expiresAt;
        this.sessionId=sessionId; this.sessionExpiresAt=sessionExpiresAt; this.idleExpiresAt=idleExpiresAt;
        this.refreshBeforeSeconds=refreshBeforeSeconds; this.credentialGeneration=credentialGeneration;
        this.refreshCookieValue=refreshCookieValue;
    }
    public String getAccessToken() { return accessToken; }
    public String getTokenType() { return "Bearer"; }
    public long getExpiresInSeconds() { return expiresInSeconds; }
    public long getExpiresAt() { return expiresAt; }
    public String getSessionId() { return sessionId; }
    public long getSessionExpiresAt() { return sessionExpiresAt; }
    public long getIdleExpiresAt() { return idleExpiresAt; }
    public long getRefreshBeforeSeconds() { return refreshBeforeSeconds; }
    public long getCredentialGeneration() { return credentialGeneration; }
    @JsonIgnore public String getRefreshCookieValue() { return refreshCookieValue; }
}
