package com.myharness.codex.websocket;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class ClientEventWebSocketHandler extends TextWebSocketHandler {
    private final Set<WebSocketSession> sessions = ConcurrentHashMap.newKeySet();
    private final ObjectMapper objectMapper;

    public ClientEventWebSocketHandler(ObjectMapper objectMapper) { this.objectMapper = objectMapper; }
    @Override public void afterConnectionEstablished(WebSocketSession session) { sessions.add(session); }
    @Override public void afterConnectionClosed(WebSocketSession session, CloseStatus status) { sessions.remove(session); }

    public void broadcast(Object event) {
        send(event,null);
    }

    public void sendToUser(Long userId,Object event) {
        if (userId==null) return;
        send(event,userId);
    }

    private void send(Object event,Long targetUserId) {
        final String json;
        try { json = objectMapper.writeValueAsString(event); }
        catch (JsonProcessingException exception) { throw new IllegalArgumentException("Unable to serialize client event", exception); }
        for (WebSocketSession session : sessions) {
            if (!session.isOpen()) { sessions.remove(session); continue; }
            if (targetUserId!=null && !targetUserId.equals(session.getAttributes().get("userId"))) continue;
            synchronized (session) {
                try { session.sendMessage(new TextMessage(json)); }
                catch (IOException exception) { sessions.remove(session); try { session.close(); } catch (IOException ignored) { } }
            }
        }
    }
}
