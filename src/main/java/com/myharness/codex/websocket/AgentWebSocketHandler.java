package com.myharness.codex.websocket;

import com.myharness.codex.entity.dto.AgentProtocolEnvelope;
import com.myharness.codex.mapper.AgentDeviceMapper;
import com.myharness.codex.service.AgentEventService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.time.LocalDateTime;

@Component
public class AgentWebSocketHandler extends TextWebSocketHandler {
    private static final Logger LOGGER=LoggerFactory.getLogger(AgentWebSocketHandler.class);
    private static final int MAX_MESSAGE_BYTES=4*1024*1024;
    private final AgentConnectionRegistry connections;
    private final AgentProtocolCodec codec;
    private final AgentEventService eventService;
    private final AgentDeviceMapper deviceMapper;

    public AgentWebSocketHandler(AgentConnectionRegistry connections,AgentProtocolCodec codec,
                                 AgentEventService eventService,AgentDeviceMapper deviceMapper) {
        this.connections=connections; this.codec=codec; this.eventService=eventService; this.deviceMapper=deviceMapper;
    }
    @Override public void afterConnectionEstablished(WebSocketSession session) {
        session.setTextMessageSizeLimit(MAX_MESSAGE_BYTES);
        Long deviceId=(Long)session.getAttributes().get(AgentHandshakeInterceptor.DEVICE_ID);
        String deviceCode=(String)session.getAttributes().get(AgentHandshakeInterceptor.DEVICE_CODE);
        connections.register(deviceCode,session); deviceMapper.markOnline(deviceId,LocalDateTime.now());
        LOGGER.info("Agent connected: {}",deviceCode);
    }
    @Override protected void handleTextMessage(WebSocketSession session,TextMessage message) throws Exception {
        String deviceCode=(String)session.getAttributes().get(AgentHandshakeInterceptor.DEVICE_CODE);
        Long deviceId=(Long)session.getAttributes().get(AgentHandshakeInterceptor.DEVICE_ID);
        try {
            AgentProtocolEnvelope envelope=codec.decodeEvent(message.getPayload(),deviceCode);
            eventService.process(deviceId,envelope);
        } catch (IllegalArgumentException exception) {
            LOGGER.warn("Rejected Agent event from {}: {}",deviceCode,exception.getMessage());
            session.close(CloseStatus.BAD_DATA.withReason("Invalid Agent protocol event"));
        }
    }
    @Override public void afterConnectionClosed(WebSocketSession session,CloseStatus status) {
        String code=(String)session.getAttributes().get(AgentHandshakeInterceptor.DEVICE_CODE);
        Long id=(Long)session.getAttributes().get(AgentHandshakeInterceptor.DEVICE_ID);
        if (connections.remove(code,session)) eventService.disconnected(id);
        LOGGER.info("Agent disconnected: {}",code);
    }
}
