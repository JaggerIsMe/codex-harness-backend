package com.myharness.codex.security;

import com.myharness.codex.config.SecurityProperties;
import com.myharness.codex.entity.enums.ErrorCode;
import com.myharness.codex.entity.vo.SessionTokenVO;
import com.myharness.codex.exception.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/** Cookie transport and explicit browser request guards; business APIs still require Bearer credentials. */
@Service
public class RefreshCookieService {
    public static final String PREFIX = "harness_refresh_";
    private static final String PATH = "/api/v1/auth";
    private final SecurityProperties properties;
    public RefreshCookieService(SecurityProperties properties) { this.properties=properties; }

    public void requireRequest(HttpServletRequest request) {
        boolean json=false;
        try { json=request.getContentType()!=null && MediaType.APPLICATION_JSON.isCompatibleWith(MediaType.parseMediaType(request.getContentType())); }
        catch (IllegalArgumentException ignored) { }
        if (!"1".equals(request.getHeader("X-Harness-Refresh")) || !json
                || "cross-site".equals(request.getHeader("Sec-Fetch-Site"))) throw new BusinessException(ErrorCode.FORBIDDEN);
        String origin=request.getHeader(HttpHeaders.ORIGIN);
        if (origin!=null && !sameOrigin(request,origin) && !properties.getRefreshAllowedOrigins().contains(origin))
            throw new BusinessException(ErrorCode.FORBIDDEN);
        secure(request);
    }

    public String requestedSession(HttpServletRequest request) {
        String sid=request.getHeader("X-Harness-Session");
        if (!validSid(sid)) throw new BusinessException(ErrorCode.UNAUTHORIZED);
        return sid;
    }

    public String credential(HttpServletRequest request,String sid) {
        if (request.getCookies()!=null) for (var cookie:request.getCookies()) {
            if ((PREFIX+sid).equals(cookie.getName())) return cookie.getValue();
        }
        throw new BusinessException(ErrorCode.UNAUTHORIZED);
    }

    public void write(HttpServletRequest request,HttpServletResponse response,SessionTokenVO grant,boolean login) {
        response.setHeader(HttpHeaders.CACHE_CONTROL,"no-store");
        if (login && request.getCookies()!=null) for (var cookie:request.getCookies()) {
            String name=cookie.getName();
            if (name.startsWith(PREFIX) && validSid(name.substring(PREFIX.length())) && !name.equals(PREFIX+grant.getSessionId()))
                clear(request,response,name.substring(PREFIX.length()));
        }
        if (grant.getRefreshCookieValue()==null || !validSid(grant.getSessionId())) throw new IllegalStateException("Missing refresh credential");
        cookie(request,response,grant.getSessionId(),grant.getRefreshCookieValue(),Math.max(0,grant.getSessionExpiresAt()-Instant.now().getEpochSecond()));
    }

    public void clear(HttpServletRequest request,HttpServletResponse response,String sid) {
        response.setHeader(HttpHeaders.CACHE_CONTROL,"no-store");
        if (validSid(sid)) cookie(request,response,sid,"",0);
    }

    private void cookie(HttpServletRequest request,HttpServletResponse response,String sid,String value,long maxAge) {
        response.addHeader(HttpHeaders.SET_COOKIE,ResponseCookie.from(PREFIX+sid,value).httpOnly(true).secure(secure(request))
                .sameSite("Strict").path(PATH).maxAge(maxAge).build().toString());
    }

    private boolean secure(HttpServletRequest request) {
        if (properties.isRefreshCookieSecure()) return true;
        if (!Set.of("localhost","127.0.0.1","::1","[::1]").contains(request.getServerName()))
            throw new BusinessException(ErrorCode.FORBIDDEN,"非本机访问必须启用安全刷新 Cookie");
        return false;
    }

    private boolean sameOrigin(HttpServletRequest request,String origin) {
        try {
            URI value=URI.create(origin);
            int port=value.getPort()<0 ? ("https".equals(value.getScheme())?443:80) : value.getPort();
            return value.getRawUserInfo()==null && value.getRawQuery()==null && value.getRawFragment()==null
                    && (value.getRawPath()==null || value.getRawPath().isEmpty())
                    && request.getScheme().equals(value.getScheme()) && request.getServerName().equals(value.getHost()) && request.getServerPort()==port;
        } catch (IllegalArgumentException exception) { return false; }
    }

    private boolean validSid(String sid) {
        try { return sid!=null && UUID.fromString(sid).toString().equals(sid); }
        catch (IllegalArgumentException exception) { return false; }
    }
}
