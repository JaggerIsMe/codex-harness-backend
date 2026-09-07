package com.myharness.codex.entity.vo;
import com.myharness.codex.entity.po.WorkspaceFileOperationPO;
public record WorkspaceFileOperationVO(String id, String kind, String path, String status, String error) {
    public WorkspaceFileOperationVO(WorkspaceFileOperationPO p) {
        this(p.getId().toString(),p.getKind(),p.getPath(),p.getStatus(),p.getError());
    }
}
