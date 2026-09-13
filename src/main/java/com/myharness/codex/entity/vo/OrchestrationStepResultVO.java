package com.myharness.codex.entity.vo;

import java.util.List;

/** A model-authored handoff, not independent proof that validation passed. */
public record OrchestrationStepResultVO(int schemaVersion, String summary, List<Long> sourceMessageIds,
        Long sourceTurnId, Long expertVersionId, boolean truncated,
        com.fasterxml.jackson.databind.JsonNode output,
        List<com.myharness.codex.entity.dto.WorkflowDTO.FileReference> files) {
    public OrchestrationStepResultVO(int schemaVersion,String summary,List<Long> sourceMessageIds,Long sourceTurnId,Long expertVersionId,boolean truncated) {
        this(schemaVersion,summary,sourceMessageIds,sourceTurnId,expertVersionId,truncated,null,null);
    }
}
