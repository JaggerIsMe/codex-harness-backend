package com.myharness.codex.entity.dto;

@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record WorkspaceFileCommandDTO(String operationId, String projectId, String workspaceName,
        String path, String cursor, long sizeBytes, String sha256,
        String targetPath,String expectedRevision,String planId,String planDigest,
        java.util.List<WorkspaceFileActionRequestDTO.Item> items,String requestDigest,Limits limits,String originalOperationId) {
    public WorkspaceFileCommandDTO(String operationId,String projectId,String workspaceName,String path,String cursor,long sizeBytes,String sha256) {
        this(operationId,projectId,workspaceName,path,cursor,sizeBytes,sha256,null,null,null,null,null,null,null,null);
    }
    public record Limits(int maxFiles,long maxFileBytes,long maxTotalBytes,long maxOutputBytes,int maxRequestBytes,int maxDurationSeconds) {}
}
