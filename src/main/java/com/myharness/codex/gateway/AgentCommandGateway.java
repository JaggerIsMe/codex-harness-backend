package com.myharness.codex.gateway;

public interface AgentCommandGateway {
    String send(String deviceCode, AgentCommand command);
    boolean isOnline(String deviceCode);
}
