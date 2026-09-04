package com.myharness.codex.config;

import com.myharness.codex.websocket.AgentHandshakeInterceptor;
import com.myharness.codex.websocket.AgentWebSocketHandler;
import com.myharness.codex.websocket.ClientEventWebSocketHandler;
import com.myharness.codex.websocket.ClientHandshakeInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {
    private final AgentWebSocketHandler agentHandler;
    private final AgentHandshakeInterceptor agentHandshake;
    private final ClientEventWebSocketHandler clientHandler;
    private final ClientHandshakeInterceptor clientHandshake;

    public WebSocketConfig(AgentWebSocketHandler agentHandler, AgentHandshakeInterceptor agentHandshake,
                           ClientEventWebSocketHandler clientHandler, ClientHandshakeInterceptor clientHandshake) {
        this.agentHandler=agentHandler; this.agentHandshake=agentHandshake;
        this.clientHandler=clientHandler; this.clientHandshake=clientHandshake;
    }

    @Override public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(agentHandler, "/ws/agent").addInterceptors(agentHandshake);
        registry.addHandler(clientHandler, "/ws/client").addInterceptors(clientHandshake).setAllowedOriginPatterns("*");
    }
}
