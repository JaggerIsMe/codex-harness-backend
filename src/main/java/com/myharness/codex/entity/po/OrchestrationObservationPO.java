package com.myharness.codex.entity.po;

/** One database snapshot of a node and its current Turn, including committed terminal evidence. */
public record OrchestrationObservationPO(Long turnId,String stepStatus,String terminalStatus,String checkpointJson,
    String turnStatus,Long expertVersionId,String failureCode,String failureMessage) {}
