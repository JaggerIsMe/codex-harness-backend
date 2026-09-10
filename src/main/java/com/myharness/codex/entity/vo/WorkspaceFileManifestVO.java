package com.myharness.codex.entity.vo;
import com.myharness.codex.entity.dto.WorkspaceFileCommandDTO;
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record WorkspaceFileManifestVO(String operationId,String projectId,String workspaceName,
        String path,String cursor,long sizeBytes,String sha256,String targetPath,String expectedRevision,String planId,String planDigest,
        java.util.List<com.myharness.codex.entity.dto.WorkspaceFileActionRequestDTO.Item> items,String requestDigest,
        WorkspaceFileCommandDTO.Limits limits,String originalOperationId) {
    public WorkspaceFileManifestVO(String operationId,String projectId,String workspaceName,String path,String cursor,long sizeBytes,String sha256) {
        this(operationId,projectId,workspaceName,path,cursor,sizeBytes,sha256,null,null,null,null,null,null,null,null);
    }
    public WorkspaceFileManifestVO(WorkspaceFileCommandDTO c) {
        this(c.operationId(),c.projectId(),c.workspaceName(),c.path(),c.cursor(),c.sizeBytes(),c.sha256(),
                c.targetPath(),c.expectedRevision(),c.planId(),c.planDigest(),c.items(),c.requestDigest(),c.limits(),c.originalOperationId());
    }
}
