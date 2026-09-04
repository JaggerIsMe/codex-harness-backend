package com.myharness.codex.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "harness.agent")
public class AgentProperties {
    private String protocolVersion = "1.0";
    private long enrollmentTtlMinutes = 10L;
    private long heartbeatTimeoutSeconds = 45L;
    private long offlineScanIntervalSeconds = 15L;
    private long commandTimeoutSeconds = 35L;
    private String publicBaseUrl;
    private String skillStorageDir;

    public String getProtocolVersion() { return protocolVersion; }
    public void setProtocolVersion(String protocolVersion) { this.protocolVersion = protocolVersion; }
    public long getEnrollmentTtlMinutes() { return enrollmentTtlMinutes; }
    public void setEnrollmentTtlMinutes(long enrollmentTtlMinutes) { this.enrollmentTtlMinutes = enrollmentTtlMinutes; }
    public long getHeartbeatTimeoutSeconds() { return heartbeatTimeoutSeconds; }
    public void setHeartbeatTimeoutSeconds(long heartbeatTimeoutSeconds) { this.heartbeatTimeoutSeconds = heartbeatTimeoutSeconds; }
    public long getOfflineScanIntervalSeconds() { return offlineScanIntervalSeconds; }
    public void setOfflineScanIntervalSeconds(long offlineScanIntervalSeconds) { this.offlineScanIntervalSeconds = offlineScanIntervalSeconds; }
    public long getCommandTimeoutSeconds() { return commandTimeoutSeconds; }
    public void setCommandTimeoutSeconds(long commandTimeoutSeconds) { this.commandTimeoutSeconds = commandTimeoutSeconds; }
    public String getPublicBaseUrl() { return publicBaseUrl; }
    public void setPublicBaseUrl(String publicBaseUrl) { this.publicBaseUrl = publicBaseUrl; }
    public String getSkillStorageDir() { return skillStorageDir; }
    public void setSkillStorageDir(String skillStorageDir) { this.skillStorageDir = skillStorageDir; }
}
