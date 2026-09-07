package com.myharness.codex.entity.vo;
import com.myharness.codex.entity.dto.WorkspaceFileCommandDTO;
public record WorkspaceFileManifestVO(String operationId,String projectId,String workspaceName,
        String path,String cursor,long sizeBytes,String sha256) {
    public WorkspaceFileManifestVO(WorkspaceFileCommandDTO c) {
        this(c.operationId(),c.projectId(),c.workspaceName(),c.path(),c.cursor(),c.sizeBytes(),c.sha256());
    }
}
