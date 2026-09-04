package com.myharness.codex.entity.vo;

import com.myharness.codex.entity.po.ConversationMessagePO;

/** APPEND content is a delta; REPLACE content is a complete snapshot. */
public class MessagePatchVO {
    private final ConversationMessageVO message;
    private final long baseRevision;
    private final String operation;
    public MessagePatchVO(ConversationMessagePO value,long baseRevision,String operation,String content) {
        this.message=new ConversationMessageVO(value,content); this.baseRevision=baseRevision; this.operation=operation;
    }
    public ConversationMessageVO getMessage(){return message;}
    public long getBaseRevision(){return baseRevision;}
    public String getOperation(){return operation;}
}
