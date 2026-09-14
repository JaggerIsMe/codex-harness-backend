package com.myharness.codex.entity.po;

public class OrchestrationStepPO {
    private String checkpointJson;
    public String getCheckpointJson(){return checkpointJson;}
    public void setCheckpointJson(String value){checkpointJson=value;}
    private Long id;
    public Long getId(){return id;}
    public void setId(Long value){id=value;}
    private Long executionId;
    public Long getExecutionId(){return executionId;}
    public void setExecutionId(Long value){executionId=value;}
    private Integer position;
    public Integer getPosition(){return position;}
    public void setPosition(Integer value){position=value;}
    private String name;
    public String getName(){return name;}
    public void setName(String value){name=value;}
    private Long expertId;
    public Long getExpertId(){return expertId;}
    public void setExpertId(Long value){expertId=value;}
    private String objective;
    public String getObjective(){return objective;}
    public void setObjective(String value){objective=value;}
    private String status;
    public String getStatus(){return status;}
    public void setStatus(String value){status=value;}
    private Long conversationId;
    public Long getConversationId(){return conversationId;}
    public void setConversationId(Long value){conversationId=value;}
    private Long turnId;
    public Long getTurnId(){return turnId;}
    public void setTurnId(Long value){turnId=value;}
    private String inputSnapshot;
    public String getInputSnapshot(){return inputSnapshot;}
    public void setInputSnapshot(String value){inputSnapshot=value;}
    private String resultJson;
    public String getResultJson(){return resultJson;}
    public void setResultJson(String value){resultJson=value;}
    private String failureMessage;
    public String getFailureMessage(){return failureMessage;}
    public void setFailureMessage(String value){failureMessage=value;}
    private String terminalStatus;
    public String getTerminalStatus(){return terminalStatus;}
    public void setTerminalStatus(String value){terminalStatus=value;}
    private java.time.LocalDateTime updatedAt;
    public java.time.LocalDateTime getUpdatedAt(){return updatedAt;}
    public void setUpdatedAt(java.time.LocalDateTime value){updatedAt=value;}
}
