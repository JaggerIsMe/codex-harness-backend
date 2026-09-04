package com.myharness.codex.entity.vo;

import java.util.List;

public class MessageUpdateVO {
    private final Long conversationId;
    private final Long turnId;
    private final long cursor;
    private final List<MessagePatchVO> patches;
    public MessageUpdateVO(Long conversationId,Long turnId,long cursor,List<MessagePatchVO> patches) {
        this.conversationId=conversationId; this.turnId=turnId; this.cursor=cursor; this.patches=patches;
    }
    public Long getConversationId(){return conversationId;}
    public Long getTurnId(){return turnId;}
    public long getCursor(){return cursor;}
    public List<MessagePatchVO> getPatches(){return patches;}
}
