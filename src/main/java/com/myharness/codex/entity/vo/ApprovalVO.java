package com.myharness.codex.entity.vo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myharness.codex.entity.po.ApprovalRequestPO;

public class ApprovalVO {
    private final Long id; private final Long conversationId; private final Long turnId; private final String approvalType;
    private final JsonNode details; private final String status;
    public ApprovalVO(ApprovalRequestPO value,ObjectMapper mapper) {
        id=value.getId(); conversationId=value.getConversationId(); turnId=value.getTurnId(); approvalType=value.getApprovalType(); status=value.getStatus();
        try { details=mapper.readTree(value.getPayload()); } catch (Exception exception) { throw new IllegalArgumentException("Invalid stored approval payload",exception); }
    }
    public Long getId(){return id;} public Long getConversationId(){return conversationId;} public Long getTurnId(){return turnId;}
    public String getApprovalType(){return approvalType;} public JsonNode getDetails(){return details;} public String getStatus(){return status;}
}
