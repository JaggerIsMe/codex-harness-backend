package com.myharness.codex.websocket;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myharness.codex.config.AgentProperties;
import com.myharness.codex.entity.dto.AgentProtocolEnvelope;
import com.myharness.codex.entity.enums.AgentCommandType;
import com.myharness.codex.entity.enums.AgentEventType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.UUID;

@Component
public class AgentProtocolCodec {
    private final ObjectMapper objectMapper;
    private final AgentProperties properties;

    public AgentProtocolCodec(ObjectMapper objectMapper, AgentProperties properties) {
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public AgentProtocolEnvelope decodeEvent(String text, String expectedDeviceCode) {
        try {
            JsonNode root = objectMapper.readTree(text);
            require(root, "protocolVersion"); require(root, "messageId"); require(root, "type");
            require(root, "timestamp"); require(root, "deviceCode"); require(root, "payload");
            AgentProtocolEnvelope envelope = objectMapper.treeToValue(root, AgentProtocolEnvelope.class);
            if (!properties.getProtocolVersion().equals(envelope.getProtocolVersion())) throw new IllegalArgumentException("Unsupported protocol version");
            if (!expectedDeviceCode.equals(envelope.getDeviceCode())) throw new IllegalArgumentException("Envelope deviceCode does not match connection");
            UUID.fromString(envelope.getMessageId());
            AgentEventType.valueOf(envelope.getType());
            return envelope;
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            throw new IllegalArgumentException("Invalid Agent protocol event: " + safe(exception.getMessage()), exception);
        }
    }

    public String encodeCommand(String deviceCode, com.myharness.codex.gateway.AgentCommand command) {
        AgentCommandType.valueOf(command.getType());
        AgentProtocolEnvelope envelope = new AgentProtocolEnvelope();
        envelope.setProtocolVersion(properties.getProtocolVersion());
        envelope.setMessageId(StringUtils.hasText(command.getMessageId()) ? command.getMessageId() : UUID.randomUUID().toString());
        envelope.setType(command.getType());
        envelope.setTimestamp(System.currentTimeMillis());
        envelope.setDeviceCode(deviceCode);
        envelope.setCorrelationId(command.getCorrelationId());
        envelope.setPayload(objectMapper.valueToTree(command.getPayload()));
        try {
            return objectMapper.writeValueAsString(envelope);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Unable to encode Agent command", exception);
        }
    }

    private void require(JsonNode node, String field) {
        if (!node.has(field) || node.get(field).isNull()) throw new IllegalArgumentException("Missing protocol field: " + field);
    }
    private String safe(String value) { return value == null || value.trim().isEmpty() ? "malformed JSON" : value; }
}
