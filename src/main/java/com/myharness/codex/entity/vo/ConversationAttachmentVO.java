package com.myharness.codex.entity.vo;
import com.myharness.codex.entity.po.ConversationAttachmentPO;
public record ConversationAttachmentVO(String id, String fileName, String mediaType, long sizeBytes, String sha256) {
    public ConversationAttachmentVO(ConversationAttachmentPO p) {
        this(String.valueOf(p.getId()),p.getFileName(),p.getMediaType(),p.getSizeBytes(),p.getSha256());
    }
}
