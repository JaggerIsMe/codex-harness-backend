package com.myharness.codex.entity.po;

import java.time.LocalDateTime;

public class ConversationMessagePO {
    private Long id; private Long conversationId; private Long turnId; private Long sequenceNo;
    private String role; private String messageType; private String content; private LocalDateTime createdAt;
    private String messageKey; private String itemId; private String phase; private String status;
    private Long revision = 0L; private String metadata; private boolean truncated;
    private LocalDateTime updatedAt; private LocalDateTime completedAt;
    public String getMessageKey(){return messageKey;} public void setMessageKey(String value){messageKey=value;}
    public String getItemId(){return itemId;} public void setItemId(String value){itemId=value;}
    public String getPhase(){return phase;} public void setPhase(String value){phase=value;}
    public String getStatus(){return status;} public void setStatus(String value){status=value;}
    public Long getRevision(){return revision;} public void setRevision(Long value){revision=value;}
    public String getMetadata(){return metadata;} public void setMetadata(String value){metadata=value;}
    public boolean isTruncated(){return truncated;} public void setTruncated(boolean value){truncated=value;}
    public LocalDateTime getUpdatedAt(){return updatedAt;} public void setUpdatedAt(LocalDateTime value){updatedAt=value;}
    public LocalDateTime getCompletedAt(){return completedAt;} public void setCompletedAt(LocalDateTime value){completedAt=value;}
    public Long getId(){return id;} public void setId(Long value){id=value;} public Long getConversationId(){return conversationId;}
    public void setConversationId(Long value){conversationId=value;} public Long getTurnId(){return turnId;} public void setTurnId(Long value){turnId=value;}
    public Long getSequenceNo(){return sequenceNo;} public void setSequenceNo(Long value){sequenceNo=value;} public String getRole(){return role;}
    public void setRole(String value){role=value;} public String getMessageType(){return messageType;} public void setMessageType(String value){messageType=value;}
    public String getContent(){return content;} public void setContent(String value){content=value;} public LocalDateTime getCreatedAt(){return createdAt;}
    public void setCreatedAt(LocalDateTime value){createdAt=value;}
}
