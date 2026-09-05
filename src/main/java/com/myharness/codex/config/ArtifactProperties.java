package com.myharness.codex.config;

import jakarta.validation.constraints.*;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

@Component
@Validated
@ConfigurationProperties(prefix="harness.artifacts")
public class ArtifactProperties {
    @NotBlank private String storageDir="./artifact-storage";
    @Min(1) private long maxFileBytes=20L*1024*1024;
    @Min(1) @Max(100) private int maxFiles=5;
    @Min(1) private long maxTotalBytes=50L*1024*1024;
    public String getStorageDir(){return storageDir;}
    public void setStorageDir(String value){storageDir=value;}
    public long getMaxFileBytes(){return maxFileBytes;}
    public void setMaxFileBytes(long value){maxFileBytes=value;}
    public int getMaxFiles(){return maxFiles;}
    public void setMaxFiles(int value){maxFiles=value;}
    public long getMaxTotalBytes(){return maxTotalBytes;}
    public void setMaxTotalBytes(long value){maxTotalBytes=value;}
}
