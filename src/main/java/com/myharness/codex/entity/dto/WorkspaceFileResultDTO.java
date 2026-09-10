package com.myharness.codex.entity.dto;

import com.myharness.codex.entity.vo.WorkspaceFileEntryVO;
import java.util.List;

public record WorkspaceFileResultDTO(String operationId, boolean success, String error,
        List<WorkspaceFileEntryVO> entries, String nextCursor, long scannedAt, long sizeBytes, String sha256,
        int version,String status,String outcome,String code,String sourcePath,String targetPath,String entryType,
        String entryRevision,Plan plan,Summary summary,List<WorkspaceFileItemsDTO.Item> items,String resultDigest) {
    public WorkspaceFileResultDTO(String operationId,boolean success,String error,List<WorkspaceFileEntryVO> entries,
                                 String nextCursor,long scannedAt,long sizeBytes,String sha256) {
        this(operationId,success,error,entries,nextCursor,scannedAt,sizeBytes,sha256,0,null,null,null,null,null,null,null,null,null,null,null);
    }
    public record Plan(String planId,String planDigest,String path,String entryType,String entryRevision,
                       long fileCount,long directoryCount,long totalBytes,long expiresAt) {}
    public record Summary(long deletedFiles,long deletedDirectories,long remainingCount) {}
}
