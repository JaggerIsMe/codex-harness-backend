package com.myharness.codex.config;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
@org.springframework.validation.annotation.Validated
@Component
@ConfigurationProperties(prefix="harness.workspace-files")
public class WorkspaceFileProperties {
    @jakarta.validation.constraints.Min(1) @jakarta.validation.constraints.Max(100) private int maxArchiveFiles=100;
    @jakarta.validation.constraints.Min(1) private long maxArchiveSourceBytes=100L*1024*1024;
    @jakarta.validation.constraints.Min(1) private long maxArchiveOutputBytes=110L*1024*1024;
    public int getMaxArchiveFiles(){return maxArchiveFiles;} public void setMaxArchiveFiles(int v){maxArchiveFiles=v;}
    public long getMaxArchiveSourceBytes(){return maxArchiveSourceBytes;} public void setMaxArchiveSourceBytes(long v){maxArchiveSourceBytes=v;}
    public long getMaxArchiveOutputBytes(){return maxArchiveOutputBytes;} public void setMaxArchiveOutputBytes(long v){maxArchiveOutputBytes=v;}
    private String storageDir="./workspace-file-storage";
    @jakarta.validation.constraints.Min(1) private long maxFileBytes=20L*1024*1024;
    @jakarta.validation.constraints.Min(1) @jakarta.validation.constraints.Max(100) private int maxFiles=5;
    @jakarta.validation.constraints.Min(1) private long maxTotalBytes=50L*1024*1024;
    public String getStorageDir(){return storageDir;} public void setStorageDir(String v){storageDir=v;}
    public long getMaxFileBytes(){return maxFileBytes;} public void setMaxFileBytes(long v){maxFileBytes=v;}
    public int getMaxFiles(){return maxFiles;} public void setMaxFiles(int v){maxFiles=v;}
    public long getMaxTotalBytes(){return maxTotalBytes;} public void setMaxTotalBytes(long v){maxTotalBytes=v;}
}
