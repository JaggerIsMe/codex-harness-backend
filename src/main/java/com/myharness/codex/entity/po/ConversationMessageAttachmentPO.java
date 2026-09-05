package com.myharness.codex.entity.po;

/** Joined attachment projection for one history page. */
public class ConversationMessageAttachmentPO extends ConversationAttachmentPO {
    private Long messageId;
    public Long getMessageId(){return messageId;}
    public void setMessageId(Long value){messageId=value;}
}
