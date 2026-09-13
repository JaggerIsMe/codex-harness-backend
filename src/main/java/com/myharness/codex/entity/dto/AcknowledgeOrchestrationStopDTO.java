package com.myharness.codex.entity.dto;

import jakarta.validation.constraints.AssertTrue;

public record AcknowledgeOrchestrationStopDTO(@AssertTrue(message="请先在设备端核实执行已停止") boolean confirmedStopped) {}
