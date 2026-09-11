package com.myharness.codex.websocket;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myharness.codex.security.*;
import com.myharness.codex.entity.enums.ErrorCode;
import com.myharness.codex.exception.BusinessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
@Component
public class ClientEventWebSocketHandler extends TextWebSocketHandler {
    public static final CloseStatus SESSION_REPLACED = new CloseStatus(4001, "SESSION_REPLACED");
    public static final CloseStatus ACCESS_TOKEN_EXPIRED = new CloseStatus(4002,"ACCESS_TOKEN_EXPIRED");
    private static final CloseStatus SESSION_UNAVAILABLE = new CloseStatus(1013, "SESSION_UNAVAILABLE");
    private final Set<WebSocketSession> sessions=ConcurrentHashMap.newKeySet();
    private final ObjectMapper json;private final UserAuthenticationService authentication;private final AuthorizationService access;
    public ClientEventWebSocketHandler(ObjectMapper json,UserAuthenticationService authentication,AuthorizationService access){
        this.json=json;this.authentication=authentication;this.access=access;
    }
    @Override public void afterConnectionEstablished(WebSocketSession session){sessions.add(session);valid(session);}
    @Override public void afterConnectionClosed(WebSocketSession session,CloseStatus status){sessions.remove(session);}
    public void broadcast(Object event){send(event,null);}
    public void sendToUser(Long userId,Object event){if(userId!=null)send(event,userId);}
    public void disconnectBeforeVersion(Long userId, long version, boolean replaced) {
        for (var session : sessions) {
            Object loginVersion = session.getAttributes().get("loginVersion");
            if (userId.equals(session.getAttributes().get("userId"))
                    && (!(loginVersion instanceof Number number) || number.longValue() < version)) {
                close(session, replaced ? SESSION_REPLACED : CloseStatus.POLICY_VIOLATION);
            }
        }
    }
    @Scheduled(fixedDelay=15000) public void expireSessions(){for(var session:sessions)valid(session);}
    private boolean valid(WebSocketSession session){
        try{
            if(!session.isOpen()){sessions.remove(session);return false;}
            var user=authentication.authenticate((String)session.getAttributes().get("token"));
            if(user.isMustChangePassword()){close(session);return false;}
            return true;
        }catch(BusinessException ex){
            close(session, ex.getErrorCode() == ErrorCode.SESSION_UNAVAILABLE ? SESSION_UNAVAILABLE
                    : ex.getErrorCode() == ErrorCode.SESSION_REPLACED ? SESSION_REPLACED
                    : ex.getErrorCode() == ErrorCode.ACCESS_TOKEN_EXPIRED ? ACCESS_TOKEN_EXPIRED : CloseStatus.POLICY_VIOLATION);
            return false;
        }catch(RuntimeException ex){close(session,SESSION_UNAVAILABLE);return false;}
    }
    private void send(Object event,Long targetUserId){
        com.fasterxml.jackson.databind.JsonNode tree=json.valueToTree(event);
        Long deviceId=tree.hasNonNull("deviceId")?tree.get("deviceId").asLong():null;
        for(var session:sessions){
            Long userId=(Long)session.getAttributes().get("userId");
            if(targetUserId!=null&&!targetUserId.equals(userId))continue;
            if(!valid(session))continue;
            if(targetUserId==null&&!access.hasPermission(userId,"device:manage"))continue;
            if(targetUserId!=null&&deviceId!=null&&!access.canUseDevice(userId,deviceId))continue;
            synchronized(session){
                try{
                    if (!session.isOpen()) { sessions.remove(session); continue; }
                    session.sendMessage(new TextMessage(json.writeValueAsString(event)));
                }
                catch(IOException | RuntimeException ex){close(session,CloseStatus.SERVER_ERROR);}
            }
        }
    }
    private void close(WebSocketSession session){
        close(session, CloseStatus.POLICY_VIOLATION);
    }
    private void close(WebSocketSession session, CloseStatus status){
        sessions.remove(session);
        synchronized(session) {
            try { if(session.isOpen()) session.close(status); }
            catch(IOException | RuntimeException ignored) { /* A single failed transport must not fail a committed login. */ }
        }
    }
}

