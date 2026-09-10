package com.myharness.codex.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myharness.codex.config.AgentProperties;
import com.myharness.codex.entity.dto.AgentProtocolEnvelope;
import com.myharness.codex.entity.dto.WorkspaceFileCommandDTO;
import com.myharness.codex.gateway.AgentCommand;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

class AgentProtocolCodecTest {
    private AgentProtocolCodec codec;
    @BeforeEach void setUp(){codec=new AgentProtocolCodec(new ObjectMapper(),new AgentProperties());}

    @Test void encodesCommandWithStableMessageId() {
        String json=codec.encodeCommand("device-1",new AgentCommand("PING",null,Collections.emptyMap(),"8ad73b79-4ff6-4b56-b0d2-434be6a42112"));
        assertTrue(json.contains("\"protocolVersion\":\"1.0\""));
        assertTrue(json.contains("\"messageId\":\"8ad73b79-4ff6-4b56-b0d2-434be6a42112\""));
        assertTrue(json.contains("\"deviceCode\":\"device-1\""));
    }

    @ParameterizedTest
    @ValueSource(strings = {"RELOCATE_WORKSPACE_ENTRY", "PREPARE_WORKSPACE_DELETE",
            "DELETE_WORKSPACE_ENTRY", "PREPARE_WORKSPACE_ARCHIVE", "RECONCILE_WORKSPACE_OPERATION"})
    void encodesWorkspaceActionCommands(String type) throws Exception {
        var payload = new WorkspaceFileCommandDTO("7", "2", "demo", "docs", "", 0, null);
        String encoded = codec.encodeCommand("device-1", new AgentCommand(type, "7", payload,
                "8ad73b79-4ff6-4b56-b0d2-434be6a42112"));
        var envelope = new ObjectMapper().readTree(encoded);
        assertEquals(type, envelope.get("type").asText());
        assertEquals("7", envelope.get("correlationId").asText());
        assertEquals("docs", envelope.path("payload").path("path").asText());
    }

    @Test void decodesValidEventAndRejectsWrongDevice() {
        String json="{\"protocolVersion\":\"1.0\",\"messageId\":\"8ad73b79-4ff6-4b56-b0d2-434be6a42112\","+
                "\"type\":\"HEARTBEAT\",\"timestamp\":1,\"deviceCode\":\"device-1\",\"payload\":{\"activeTurnCount\":0}}";
        AgentProtocolEnvelope envelope=codec.decodeEvent(json,"device-1");
        assertEquals("HEARTBEAT",envelope.getType());
        assertThrows(IllegalArgumentException.class,()->codec.decodeEvent(json,"device-2"));
    }

    @Test void rejectsUnknownEventTypeAndMissingPayload() {
        String unknown="{\"protocolVersion\":\"1.0\",\"messageId\":\"8ad73b79-4ff6-4b56-b0d2-434be6a42112\","+
                "\"type\":\"UNKNOWN\",\"timestamp\":1,\"deviceCode\":\"device-1\",\"payload\":{}}";
        assertThrows(IllegalArgumentException.class,()->codec.decodeEvent(unknown,"device-1"));
        assertThrows(IllegalArgumentException.class,()->codec.decodeEvent(unknown.replace(",\"payload\":{}",""),"device-1"));
    }
}
