package com.myharness.codex.entity.vo;

import com.myharness.codex.entity.po.OrchestrationExecutionPO;
import com.myharness.codex.entity.dto.WorkflowDTO;
import java.time.LocalDateTime;
import java.util.List;

public record OrchestrationVO(Long id, Long projectId, String title, String goal, String status,
    String failureMessage, LocalDateTime createdAt, List<OrchestrationStepVO> steps, WorkflowDTO workflow) {
    public OrchestrationVO(OrchestrationExecutionPO p,List<OrchestrationStepVO> steps) {
        this(p,steps,null);
    }
    public OrchestrationVO(OrchestrationExecutionPO p,List<OrchestrationStepVO> steps,WorkflowDTO workflow) {
        this(p.getId(),p.getProjectId(),p.getTitle(),p.getGoal(),p.getStatus(),p.getFailureMessage(),p.getCreatedAt(),steps,workflow);
    }
}
