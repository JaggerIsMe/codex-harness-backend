package com.myharness.codex.entity.vo;

import com.myharness.codex.entity.po.ConversationMessagePO;
import java.time.LocalDateTime;

public class ConversationMessageVO {
    private final Long id; private final Long turnId; private final Long sequenceNo; private final String role;
    private final String messageType; private final String content; private final LocalDateTime createdAt;
    public ConversationMessageVO(ConversationMessagePO value){this(value,value.getContent());}
    public ConversationMessageVO(ConversationMessagePO value,String body){id=value.getId();turnId=value.getTurnId();sequenceNo=value.getSequenceNo();role=value.getRole();messageType=value.getMessageType();content=body;createdAt=value.getCreatedAt();
        messageKey=value.getMessageKey(); itemId=value.getItemId(); phase=value.getPhase(); status=value.getStatus();
        revision=value.getRevision(); metadata=value.getMetadata(); truncated=value.isTruncated();}
    public Long getId(){return id;} public Long getTurnId(){return turnId;} public Long getSequenceNo(){return sequenceNo;}
    public String getRole(){return role;} public String getMessageType(){return messageType;} public String getContent(){return content;}
    public LocalDateTime getCreatedAt(){return createdAt;}
    private String messageKey; private String itemId; private String phase; private String status;
    private Long revision; private String metadata; private boolean truncated;
    public String getMessageKey(){return messageKey;} public String getItemId(){return itemId;}
    public String getPhase(){return phase;} public String getStatus(){return status;}
    public Long getRevision(){return revision;} public String getMetadata(){return metadata;}
    public boolean isTruncated(){return truncated;} public boolean isStreaming(){return "STREAMING".equals(status);}
}
