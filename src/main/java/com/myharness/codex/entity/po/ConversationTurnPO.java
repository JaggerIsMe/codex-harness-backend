package com.myharness.codex.entity.po;

public class ConversationTurnPO {
    private Long id;
    private Long conversationId;
    private String codexTurnId;
    private String status;
    public Long getId() { return id; }
    public void setId(Long value) { id=value; }
    public Long getConversationId() { return conversationId; }
    public void setConversationId(Long value) { conversationId=value; }
    public String getCodexTurnId() { return codexTurnId; }
    public void setCodexTurnId(String value) { codexTurnId=value; }
    public String getStatus() { return status; }
    public void setStatus(String value) { status=value; }
}
