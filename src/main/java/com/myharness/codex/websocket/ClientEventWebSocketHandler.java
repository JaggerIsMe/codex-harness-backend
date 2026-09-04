package com.myharness.codex.websocket;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myharness.codex.security.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
@Component
public class ClientEventWebSocketHandler extends TextWebSocketHandler {
    private final Set<WebSocketSession> sessions=ConcurrentHashMap.newKeySet();
    private final ObjectMapper json;private final UserAuthenticationService authentication;private final AuthorizationService access;
    public ClientEventWebSocketHandler(ObjectMapper json,UserAuthenticationService authentication,AuthorizationService access){
        this.json=json;this.authentication=authentication;this.access=access;
    }
    @Override public void afterConnectionEstablished(WebSocketSession session){sessions.add(session);}
    @Override public void afterConnectionClosed(WebSocketSession session,CloseStatus status){sessions.remove(session);}
    public void broadcast(Object event){send(event,null);}
    public void sendToUser(Long userId,Object event){if(userId!=null)send(event,userId);}
    public void disconnectUser(Long userId){for(var session:sessions)if(userId.equals(session.getAttributes().get("userId")))close(session);}
    @Scheduled(fixedDelay=15000) public void expireSessions(){for(var session:sessions)valid(session);}
    private boolean valid(WebSocketSession session){
        try{
            if(!session.isOpen()){sessions.remove(session);return false;}
            var user=authentication.authenticate((String)session.getAttributes().get("token"));
            if(user.isMustChangePassword()){close(session);return false;}
            return true;
        }catch(RuntimeException ex){close(session);return false;}
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
                try{session.sendMessage(new TextMessage(json.writeValueAsString(event)));}
                catch(IOException ex){close(session);}
            }
        }
    }
    private void close(WebSocketSession session){
        sessions.remove(session);try{session.close(CloseStatus.POLICY_VIOLATION);}catch(IOException ignored){}
    }
}

