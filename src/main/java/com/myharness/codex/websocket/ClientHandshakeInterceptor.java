package com.myharness.codex.websocket;
import com.myharness.codex.security.*;
import org.springframework.http.*;
import org.springframework.http.server.*;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriComponentsBuilder;
import java.util.Map;
@Component
public class ClientHandshakeInterceptor implements HandshakeInterceptor {
    private final UserAuthenticationService authentication;private final ClientSocketTickets tickets;
    public ClientHandshakeInterceptor(UserAuthenticationService authentication,ClientSocketTickets tickets){this.authentication=authentication;this.tickets=tickets;}
    @Override public boolean beforeHandshake(ServerHttpRequest request,ServerHttpResponse response,WebSocketHandler handler,Map<String,Object> attributes) {
        try {
            String ticket=UriComponentsBuilder.fromUri(request.getURI()).build().getQueryParams().getFirst("ticket");
            String token=tickets.consume(ticket);var user=authentication.authenticate(token);
            if(user.isMustChangePassword()){response.setStatusCode(HttpStatus.FORBIDDEN);return false;}
            attributes.put("userId",user.getId());attributes.put("token",token);return true;
        } catch(RuntimeException ex){response.setStatusCode(HttpStatus.UNAUTHORIZED);return false;}
    }
    @Override public void afterHandshake(ServerHttpRequest request,ServerHttpResponse response,WebSocketHandler handler,Exception ex){}
}

