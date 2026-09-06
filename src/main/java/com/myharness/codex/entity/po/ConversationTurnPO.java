package com.myharness.codex.entity.po;

public class ConversationTurnPO {
    private Long expertVersionId;
    public Long getExpertVersionId() {return expertVersionId;}
    public void setExpertVersionId(Long value) {expertVersionId=value;}
    private String expertName;
    public String getExpertName() {return expertName;}
    public void setExpertName(String value) {expertName=value;}
    private String expertRuntime;
    public String getExpertRuntime() {return expertRuntime;}
    public void setExpertRuntime(String value) {expertRuntime=value;}
    private String clientRequestId;
    public String getClientRequestId(){return clientRequestId;}
    public void setClientRequestId(String value){clientRequestId=value;}
    private String requestHash;
    public String getRequestHash(){return requestHash;}
    public void setRequestHash(String value){requestHash=value;}
    private String preparationPhase;
    public String getPreparationPhase(){return preparationPhase;}
    public void setPreparationPhase(String value){preparationPhase=value;}

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
