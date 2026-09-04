package com.myharness.codex.service;

import com.myharness.codex.entity.dto.ApprovalDecisionDTO;
import com.myharness.codex.entity.po.ApprovalRequestPO;

public interface ApprovalService {
    ApprovalRequestPO decide(Long approvalId, ApprovalDecisionDTO dto, Long operatorId);
}
