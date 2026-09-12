package com.myharness.codex.service.impl;

import com.myharness.codex.entity.dto.ApprovalDecisionDTO;
import com.myharness.codex.entity.enums.ErrorCode;
import com.myharness.codex.entity.po.ApprovalRequestPO;
import com.myharness.codex.exception.BusinessException;
import com.myharness.codex.gateway.AgentCommand;
import com.myharness.codex.gateway.AgentCommandGateway;
import com.myharness.codex.mapper.ApprovalMapper;
import com.myharness.codex.service.ApprovalService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class ApprovalServiceImpl implements ApprovalService {
    private final ApprovalMapper mapper;
    private final AgentCommandGateway gateway;
    private final com.myharness.codex.security.AuthorizationService access;
    public ApprovalServiceImpl(ApprovalMapper mapper,AgentCommandGateway gateway,com.myharness.codex.security.AuthorizationService access) {
        this.mapper=mapper; this.gateway=gateway; this.access=access;
    }

    @Override @Transactional
    public ApprovalRequestPO decide(Long approvalId,ApprovalDecisionDTO dto,Long operatorId) {
        access.requirePermission(operatorId,"approval:decide");
        String decision=dto.getDecision().trim().toUpperCase();
        String status;
        if ("ACCEPT".equals(decision) || "ACCEPT_FOR_SESSION".equals(decision)) status="APPROVED";
        else if ("DECLINE".equals(decision)) status="REJECTED";
        else if ("CANCEL".equals(decision)) status="CANCELLED";
        else throw new BusinessException(ErrorCode.INVALID_REQUEST,"不支持的审批决定");
        ApprovalRequestPO approval=mapper.selectOwnedById(approvalId,operatorId);
        if (approval==null) throw new BusinessException(ErrorCode.NOT_FOUND,"审批不存在");
        access.requireDevice(operatorId,approval.getDeviceId());
        if ("ACCEPT_FOR_SESSION".equals(decision))
            throw new BusinessException(ErrorCode.INVALID_REQUEST,"审批仅支持本次决定，不支持本会话批准");
        com.fasterxml.jackson.databind.JsonNode answers=null;
        if("MCP_TOOL_CALL".equals(approval.getApprovalType())&&"ACCEPT".equals(decision)) {
            try { answers=ToolQuestionAnswers.validate(new com.fasterxml.jackson.databind.ObjectMapper().readTree(approval.getPayload()),dto.getAnswers()); }
            catch(com.fasterxml.jackson.core.JsonProcessingException e) {throw new BusinessException(ErrorCode.INVALID_REQUEST,"原始问题格式无效");}
        } else if(dto.getAnswers()!=null&&!dto.getAnswers().isNull())
            throw new BusinessException(ErrorCode.INVALID_REQUEST,"当前决定不接受问题答案");
        if (!gateway.isOnline(approval.getDeviceCode())) throw new BusinessException(ErrorCode.AGENT_OFFLINE);
        if (mapper.decide(approvalId,status,operatorId,LocalDateTime.now())!=1)
            throw new BusinessException(ErrorCode.CONFLICT,"审批已被处理");
        Map<String,Object> payload=new LinkedHashMap<>(); payload.put("requestId",approval.getRemoteRequestId()); payload.put("decision",decision);
        String messageId=java.util.UUID.randomUUID().toString();
        if(mapper.recordDispatch(approvalId,messageId)!=1)throw new BusinessException(ErrorCode.CONFLICT,"审批提交状态已变化");
        payload.put("decisionMessageId",messageId);
        if(answers!=null)payload.put("answers",answers);
        gateway.send(approval.getDeviceCode(),new AgentCommand("RESOLVE_APPROVAL",String.valueOf(approvalId),payload,messageId));
        return mapper.selectOwnedById(approvalId,operatorId);
    }
}
