package com.myharness.codex.service;

import com.myharness.codex.entity.dto.AgentProtocolEnvelope;

public interface AgentEventService {
    boolean process(Long deviceId, AgentProtocolEnvelope envelope);
    void disconnected(Long deviceId);
}
