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
        if ("ACCEPT_FOR_SESSION".equals(decision) && !access.hasPermission(operatorId,"device:manage"))
            throw new BusinessException(ErrorCode.FORBIDDEN,"普通用户仅可批准当前请求");
        if (!gateway.isOnline(approval.getDeviceCode())) throw new BusinessException(ErrorCode.AGENT_OFFLINE);
        if (mapper.decide(approvalId,status,operatorId,LocalDateTime.now())!=1)
            throw new BusinessException(ErrorCode.CONFLICT,"审批已被处理");
        Map<String,Object> payload=new LinkedHashMap<>(); payload.put("requestId",approval.getRemoteRequestId()); payload.put("decision",decision);
        gateway.send(approval.getDeviceCode(),new AgentCommand("RESOLVE_APPROVAL",String.valueOf(approvalId),payload));
        return mapper.selectOwnedById(approvalId,operatorId);
    }
}
