package com.myharness.codex.websocket;

import com.myharness.codex.entity.enums.UserStatus;
import com.myharness.codex.entity.po.SysUserPO;
import com.myharness.codex.mapper.SysUserMapper;
import com.myharness.codex.security.JwtTokenService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Map;

@Component
public class ClientHandshakeInterceptor implements HandshakeInterceptor {
    private final JwtTokenService jwtTokenService;
    private final SysUserMapper userMapper;

    public ClientHandshakeInterceptor(JwtTokenService jwtTokenService, SysUserMapper userMapper) {
        this.jwtTokenService = jwtTokenService;
        this.userMapper = userMapper;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String,Object> attributes) {
        String authorization = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        String token = StringUtils.hasText(authorization) && authorization.startsWith("Bearer ")
                ? authorization.substring(7).trim()
                : UriComponentsBuilder.fromUri(request.getURI()).build().getQueryParams().getFirst("access_token");
        try {
            Long userId = jwtTokenService.parseUserId(token);
            SysUserPO user = userMapper.selectById(userId);
            if (user == null || !UserStatus.ENABLED.name().equals(user.getStatus())) throw new IllegalArgumentException();
            attributes.put("userId", userId);
            return true;
        } catch (RuntimeException exception) {
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }
    }
    @Override public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                          WebSocketHandler wsHandler, Exception exception) { }
}
