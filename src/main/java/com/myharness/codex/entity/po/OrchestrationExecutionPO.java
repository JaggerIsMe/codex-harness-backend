package com.myharness.codex.entity.po;

public class OrchestrationExecutionPO {
    private boolean cancelRequested;
    public boolean isCancelRequested(){return cancelRequested;}
    public void setCancelRequested(boolean value){cancelRequested=value;}
    private Long id;
    public Long getId(){return id;}
    public void setId(Long value){id=value;}
    private Long projectId;
    public Long getProjectId(){return projectId;}
    public void setProjectId(Long value){projectId=value;}
    private Long userId;
    public Long getUserId(){return userId;}
    public void setUserId(Long value){userId=value;}
    private Long deviceId;
    public Long getDeviceId(){return deviceId;}
    public void setDeviceId(Long value){deviceId=value;}
    private String title;
    public String getTitle(){return title;}
    public void setTitle(String value){title=value;}
    private String goal;
    public String getGoal(){return goal;}
    public void setGoal(String value){goal=value;}
    private String requestKey;
    public String getRequestKey(){return requestKey;}
    public void setRequestKey(String value){requestKey=value;}
    private String requestHash;
    public String getRequestHash(){return requestHash;}
    public void setRequestHash(String value){requestHash=value;}
    private String planJson;
    public String getPlanJson(){return planJson;}
    public void setPlanJson(String value){planJson=value;}
    private String status;
    public String getStatus(){return status;}
    public void setStatus(String value){status=value;}
    private String failureMessage;
    public String getFailureMessage(){return failureMessage;}
    public void setFailureMessage(String value){failureMessage=value;}
    private java.time.LocalDateTime createdAt;
    public java.time.LocalDateTime getCreatedAt(){return createdAt;}
    public void setCreatedAt(java.time.LocalDateTime value){createdAt=value;}
    private java.time.LocalDateTime updatedAt;
    public java.time.LocalDateTime getUpdatedAt(){return updatedAt;}
    public void setUpdatedAt(java.time.LocalDateTime value){updatedAt=value;}
}
