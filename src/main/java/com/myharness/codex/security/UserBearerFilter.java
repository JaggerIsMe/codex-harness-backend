package com.myharness.codex.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myharness.codex.entity.enums.ErrorCode;
import com.myharness.codex.entity.po.SysUserPO;
import com.myharness.codex.entity.vo.ApiResponseVO;
import com.myharness.codex.exception.BusinessException;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.util.Set;

public class UserBearerFilter extends OncePerRequestFilter {
    private final UserAuthenticationService authentication;
    private final AuthorizationService authorization;
    private final ObjectMapper json;
    private static final Set<String> PASSWORD_ROUTES=Set.of("/api/v1/auth/profile","/api/v1/auth/change-password","/api/v1/auth/logout");
    public UserBearerFilter(UserAuthenticationService authentication,AuthorizationService authorization,ObjectMapper json) {
        this.authentication=authentication; this.authorization=authorization; this.json=json;
    }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)
            throws ServletException,IOException {
        String path=request.getServletPath();
        // These endpoints use Enrollment/Device credentials, or one-time WebSocket tickets.
        if(!path.startsWith("/api/v1/") || PublicAuthEndpoints.allows(request.getMethod(), path) || path.startsWith("/api/v1/agent/")) {
            chain.doFilter(request,response); return;
        }
        try {
            String header=request.getHeader("Authorization");
            if(header==null || !header.startsWith("Bearer ")) throw new BusinessException(ErrorCode.UNAUTHORIZED);
            SysUserPO user=authentication.authenticate(header.substring(7).trim());
            if(user.isMustChangePassword() && !PASSWORD_ROUTES.contains(path)) throw new BusinessException(ErrorCode.PASSWORD_CHANGE_REQUIRED);
            UserPrincipal principal=new UserPrincipal(user.getId(),user.getEmail(),user.getDisplayName());
            var authorities=authorization.permissions(user.getId()).stream().map(SimpleGrantedAuthority::new).toList();
            SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(principal,null,authorities));
            UserContext.set(principal);
        } catch(BusinessException exception) {
            SecurityContextHolder.clearContext(); UserContext.clear();
            response.setStatus(exception.getErrorCode().getHttpStatus().value());
            response.setContentType("application/json;charset=UTF-8");
            json.writeValue(response.getOutputStream(),ApiResponseVO.error(exception.getErrorCode().getCode(),exception.getMessage()));
            return;
        }
        try { chain.doFilter(request,response); }
        finally { UserContext.clear(); SecurityContextHolder.clearContext(); }
    }
}
