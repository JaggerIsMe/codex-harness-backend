package com.myharness.codex.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "harness.security")
public class SecurityProperties {

    private String jwtSecret;
    private String modelSecretKey;
    private long jwtExpireMinutes = 120L;

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
}
