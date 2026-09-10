package com.myharness.codex.entity.vo;
import java.util.List;
public record WorkspaceDirectoryVO(String path, String generation, long scannedAt,
        List<WorkspaceFileEntryVO> entries, String nextCursor, boolean loaded, boolean online,
        boolean supported, WorkspaceFileOperationVO operation, long maxFileBytes,
        WorkspaceFileCapabilitiesVO capabilities,WorkspaceFileLimitsVO limits) {
    public WorkspaceDirectoryVO(String path,String generation,long scannedAt,List<WorkspaceFileEntryVO> entries,String nextCursor,
                                boolean loaded,boolean online,boolean supported,WorkspaceFileOperationVO operation,long maxFileBytes) {
        this(path,generation,scannedAt,entries,nextCursor,loaded,online,supported,operation,maxFileBytes,null,null);
    }
}
