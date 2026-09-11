package com.myharness.codex.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "harness.security")
public class SecurityProperties {

    private String jwtSecret;
    private String modelSecretKey;
    private long jwtExpireMinutes = 120L;
    private long sessionIdleMinutes = 120L;
    private long sessionAbsoluteDays = 7L;
    private long refreshBeforeSeconds = 600L;
    private long refreshReplayGraceSeconds = 10L;
    private boolean refreshCookieSecure = true;
    private java.util.List<String> refreshAllowedOrigins = java.util.List.of("http://localhost:8010", "http://127.0.0.1:8010");

    public String getJwtSecret() {
        return jwtSecret;
    }

    public void setJwtSecret(String jwtSecret) {
        this.jwtSecret = jwtSecret;
    }

    public String getModelSecretKey() { return modelSecretKey; }
    public void setModelSecretKey(String value) { modelSecretKey = value; }

    public long getJwtExpireMinutes() {
        return jwtExpireMinutes;
    }

    public void setJwtExpireMinutes(long jwtExpireMinutes) {
        this.jwtExpireMinutes = jwtExpireMinutes;
    }
    public long getSessionIdleMinutes() { return sessionIdleMinutes; }
    public void setSessionIdleMinutes(long value) { sessionIdleMinutes = value; }
    public long getSessionAbsoluteDays() { return sessionAbsoluteDays; }
    public void setSessionAbsoluteDays(long value) { sessionAbsoluteDays = value; }
    public long getRefreshBeforeSeconds() { return refreshBeforeSeconds; }
    public void setRefreshBeforeSeconds(long value) { refreshBeforeSeconds = value; }
    public long getRefreshReplayGraceSeconds() { return refreshReplayGraceSeconds; }
    public void setRefreshReplayGraceSeconds(long value) { refreshReplayGraceSeconds = value; }
    public boolean isRefreshCookieSecure() { return refreshCookieSecure; }
    public void setRefreshCookieSecure(boolean value) { refreshCookieSecure = value; }
    public java.util.List<String> getRefreshAllowedOrigins() { return refreshAllowedOrigins; }
    public void setRefreshAllowedOrigins(java.util.List<String> value) { refreshAllowedOrigins = java.util.List.copyOf(value); }
    @jakarta.annotation.PostConstruct
    public void validateSessionPolicy() {
        if (jwtExpireMinutes<=0 || jwtExpireMinutes>43200 || sessionIdleMinutes<=0 || sessionAbsoluteDays<=0 || sessionAbsoluteDays>30
                || sessionIdleMinutes>sessionAbsoluteDays*1440 || jwtExpireMinutes>sessionAbsoluteDays*1440
                || refreshBeforeSeconds<0 || refreshBeforeSeconds>=jwtExpireMinutes*60
                || refreshReplayGraceSeconds<0 || refreshReplayGraceSeconds>60)
            throw new IllegalStateException("Invalid authentication lifetime configuration");
    }
}
