package com.myharness.codex.entity.vo;

import com.myharness.codex.entity.po.ConversationArtifactPO;
public record ConversationArtifactVO(String id, String turnId, String fileName, String mediaType,
        long sizeBytes, String sha256, String status, String errorMessage) {
    public ConversationArtifactVO(ConversationArtifactPO p) {
        this(p.getId().toString(),p.getTurnId().toString(),p.getFileName(),p.getMediaType(),
                p.getSizeBytes(),p.getSha256(),p.getStatus(),p.getErrorMessage());
    }
}
