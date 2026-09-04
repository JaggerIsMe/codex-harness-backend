package com.myharness.codex.websocket;

import com.myharness.codex.entity.enums.ErrorCode;
import com.myharness.codex.exception.BusinessException;
import com.myharness.codex.gateway.AgentCommand;
import com.myharness.codex.gateway.AgentCommandGateway;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class AgentConnectionRegistry implements AgentCommandGateway {
    private final ConcurrentHashMap<String,WebSocketSession> sessions = new ConcurrentHashMap<>();
    private final AgentProtocolCodec codec;

    public AgentConnectionRegistry(AgentProtocolCodec codec) { this.codec = codec; }

    public void register(String deviceCode, WebSocketSession session) {
        WebSocketSession previous = sessions.put(deviceCode, session);
        if (previous != null && previous != session) close(previous, CloseStatus.NORMAL.withReason("Replaced by a newer connection"));
    }

    public boolean remove(String deviceCode, WebSocketSession session) { return sessions.remove(deviceCode, session); }

    public void disconnect(String deviceCode, String reason) {
        WebSocketSession session = sessions.remove(deviceCode);
        if (session != null) close(session, CloseStatus.NORMAL.withReason(reason));
    }

    @Override
    public String send(String deviceCode, AgentCommand command) {
        WebSocketSession session = sessions.get(deviceCode);
        if (session == null || !session.isOpen()) throw new BusinessException(ErrorCode.AGENT_OFFLINE);
        String messageId = command.getMessageId() == null ? UUID.randomUUID().toString() : command.getMessageId();
        String encoded = codec.encodeCommand(deviceCode,
                new AgentCommand(command.getType(), command.getCorrelationId(), command.getPayload(), messageId));
        synchronized (session) {
            try { session.sendMessage(new TextMessage(encoded)); }
            catch (IOException exception) {
                sessions.remove(deviceCode, session);
                close(session, CloseStatus.SERVER_ERROR);
                throw new BusinessException(ErrorCode.AGENT_OFFLINE);
            }
        }
        return messageId;
    }

    @Override public boolean isOnline(String deviceCode) {
        WebSocketSession session = sessions.get(deviceCode);
        return session != null && session.isOpen();
    }

    private void close(WebSocketSession session, CloseStatus status) {
        try { if (session.isOpen()) session.close(status); } catch (IOException ignored) { }
    }
}
