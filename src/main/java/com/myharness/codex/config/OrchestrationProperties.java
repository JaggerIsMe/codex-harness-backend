package com.myharness.codex.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;

@Component
@Validated
@ConfigurationProperties("harness.orchestration")
public class OrchestrationProperties {
    private boolean enabled;
    @Min(1) @Max(32) private int maxActiveTurnsPerDevice=4;
    @Min(1) @Max(100) private int maxQueuedPerUser=10;
    @Min(1) @Max(1440) private int executionTimeoutMinutes=60;
    public boolean isEnabled(){return enabled;}
    public void setEnabled(boolean value){enabled=value;}
    public int getMaxActiveTurnsPerDevice(){return maxActiveTurnsPerDevice;}
    public void setMaxActiveTurnsPerDevice(int value){maxActiveTurnsPerDevice=value;}
    public int getMaxQueuedPerUser(){return maxQueuedPerUser;}
    public void setMaxQueuedPerUser(int value){maxQueuedPerUser=value;}
    public int getExecutionTimeoutMinutes(){return executionTimeoutMinutes;}
    public void setExecutionTimeoutMinutes(int value){executionTimeoutMinutes=value;}
}
