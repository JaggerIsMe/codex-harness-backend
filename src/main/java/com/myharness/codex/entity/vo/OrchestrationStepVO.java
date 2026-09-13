package com.myharness.codex.entity.vo;

import com.myharness.codex.entity.po.OrchestrationStepPO;
public record OrchestrationStepVO(Long id, int position, String name, Long expertId, String objective,
    String status, Long conversationId, Long turnId, String failureMessage, OrchestrationStepResultVO result) {
    public OrchestrationStepVO(OrchestrationStepPO p, OrchestrationStepResultVO result) {
        this(p.getId(),p.getPosition(),p.getName(),p.getExpertId(),p.getObjective(),p.getStatus(),
            p.getConversationId(),p.getTurnId(),p.getFailureMessage(),result);
    }
}
