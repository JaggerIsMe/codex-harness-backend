package com.myharness.codex.websocket;

import com.myharness.codex.config.AgentProperties;
import com.myharness.codex.entity.po.AgentDevicePO;
import com.myharness.codex.exception.BusinessException;
import com.myharness.codex.security.DeviceAuthenticationService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;

@Component
public class AgentHandshakeInterceptor implements HandshakeInterceptor {
    public static final String DEVICE_ID = "agentDeviceId";
    public static final String DEVICE_CODE = "agentDeviceCode";
    private final DeviceAuthenticationService authenticationService;
    private final AgentProperties properties;

    public AgentHandshakeInterceptor(DeviceAuthenticationService authenticationService, AgentProperties properties) {
        this.authenticationService = authenticationService;
        this.properties = properties;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String,Object> attributes) {
        HttpHeaders headers = request.getHeaders();
        if (!properties.getProtocolVersion().equals(headers.getFirst("X-Harness-Protocol-Version"))) {
            response.setStatusCode(HttpStatus.UPGRADE_REQUIRED);
            return false;
        }
        try {
            AgentDevicePO device = authenticationService.authenticate(headers.getFirst("X-Harness-Device-Code"),
                    headers.getFirst(HttpHeaders.AUTHORIZATION));
            attributes.put(DEVICE_ID, device.getId());
            attributes.put(DEVICE_CODE, device.getDeviceCode());
            return true;
        } catch (BusinessException exception) {
            response.setStatusCode(exception.getErrorCode().getHttpStatus());
            return false;
        }
    }

    @Override public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                          WebSocketHandler wsHandler, Exception exception) { }
}
