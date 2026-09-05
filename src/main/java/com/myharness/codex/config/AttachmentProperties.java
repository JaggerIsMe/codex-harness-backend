package com.myharness.codex.config;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
@org.springframework.validation.annotation.Validated
@Component
@ConfigurationProperties(prefix="harness.attachments")
public class AttachmentProperties {
    private String storageDir="./attachment-storage";
    @jakarta.validation.constraints.Min(1) private long maxFileBytes=20L*1024*1024;
    @jakarta.validation.constraints.Min(1) @jakarta.validation.constraints.Max(100) private int maxFiles=5;
    @jakarta.validation.constraints.Min(1) private long maxTotalBytes=50L*1024*1024;
    public String getStorageDir(){return storageDir;} public void setStorageDir(String v){storageDir=v;}
    public long getMaxFileBytes(){return maxFileBytes;} public void setMaxFileBytes(long v){maxFileBytes=v;}
    public int getMaxFiles(){return maxFiles;} public void setMaxFiles(int v){maxFiles=v;}
    public long getMaxTotalBytes(){return maxTotalBytes;} public void setMaxTotalBytes(long v){maxTotalBytes=v;}
}
