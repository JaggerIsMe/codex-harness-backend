package com.myharness.codex.gateway;

public class AgentCommand {
    private final String type;
    private final String correlationId;
    private final Object payload;
    private final String messageId;

    public AgentCommand(String type, String correlationId, Object payload) {
        this(type, correlationId, payload, null);
    }
    public AgentCommand(String type, String correlationId, Object payload, String messageId) {
        this.type = type;
        this.correlationId = correlationId;
        this.payload = payload;
        this.messageId = messageId;
    }
    public String getType() { return type; }
    public String getCorrelationId() { return correlationId; }
    public Object getPayload() { return payload; }
    public String getMessageId() { return messageId; }
}
