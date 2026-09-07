package com.myharness.codex.entity.vo;
import com.myharness.codex.entity.po.ConversationAttachmentPO;
public record ConversationAttachmentVO(String id, String fileName, String mediaType, long sizeBytes, String sha256,
        String workspacePath, String workspaceOperationId) {
    public ConversationAttachmentVO(String id,String fileName,String mediaType,long sizeBytes,String sha256) {
        this(id,fileName,mediaType,sizeBytes,sha256,null,null);
    }
    public ConversationAttachmentVO(ConversationAttachmentPO p) {
        this(String.valueOf(p.getId()),p.getFileName(),p.getMediaType(),p.getSizeBytes(),p.getSha256(),p.getWorkspacePath(),
                p.getWorkspaceOperationId()==null ? null : p.getWorkspaceOperationId().toString());
    }
}
