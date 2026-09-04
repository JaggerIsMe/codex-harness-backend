package com.myharness.codex.entity.po;

public class ApprovalRequestPO {
    private Long id;
    private Long conversationId;
    private Long turnId;
    private Long deviceId;
    private String remoteRequestId;
    private String approvalType;
    private String payload;
    private String status;
    private Long decidedBy;
    private String deviceCode;

    public Long getId() { return id; }
    public void setId(Long value) { id=value; }
    public Long getConversationId() { return conversationId; }
    public void setConversationId(Long value) { conversationId=value; }
    public Long getTurnId() { return turnId; }
    public void setTurnId(Long value) { turnId=value; }
    public Long getDeviceId() { return deviceId; }
    public void setDeviceId(Long value) { deviceId=value; }
    public String getRemoteRequestId() { return remoteRequestId; }
    public void setRemoteRequestId(String value) { remoteRequestId=value; }
    public String getApprovalType() { return approvalType; }
    public void setApprovalType(String value) { approvalType=value; }
    public String getPayload() { return payload; }
    public void setPayload(String value) { payload=value; }
    public String getStatus() { return status; }
    public void setStatus(String value) { status=value; }
    public Long getDecidedBy() { return decidedBy; }
    public void setDecidedBy(Long value) { decidedBy=value; }
    public String getDeviceCode() { return deviceCode; }
    public void setDeviceCode(String value) { deviceCode=value; }
}
