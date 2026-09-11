package com.myharness.codex.security;

import com.myharness.codex.entity.enums.ErrorCode;
import com.myharness.codex.entity.po.SysUserPO;
import com.myharness.codex.exception.BusinessException;
import com.myharness.codex.mapper.SysUserMapper;
import org.springframework.stereotype.Service;

@Service
public class UserAuthenticationService {
    public record AuthenticatedUser(SysUserPO user, RedisLoginSessionStore.Session session) {}

    private final JwtTokenService jwt;
    private final SysUserMapper users;
    private final RedisLoginSessionStore sessions;

    public UserAuthenticationService(JwtTokenService jwt, SysUserMapper users, RedisLoginSessionStore sessions) {
        this.jwt=jwt; this.users=users; this.sessions=sessions;
    }

    public SysUserPO authenticate(String token) {
        return authenticateSession(token).user();
    }

    public AuthenticatedUser authenticateSession(String token) {
        var claims = jwt.parseClaimsAllowExpired(token);
        var session = jwt.session(claims);
        SysUserPO user = users.selectById(Long.valueOf(claims.getSubject()));
        if (user == null || !user.isActivated() || !"ENABLED".equals(user.getStatus())) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        var current = sessions.read(user.getId());
        if (current != null && current.active(java.time.Instant.now().getEpochSecond()) && current.version() == user.getTokenVersion()
                && current.version() >= session.version() && !current.sid().equals(session.sid())) {
            throw new BusinessException(ErrorCode.SESSION_REPLACED);
        }
        if (user.getTokenVersion() != session.version() || !session.sameLogin(current)
                || !current.active(java.time.Instant.now().getEpochSecond())) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        if (claims.getExpiration().getTime()<=System.currentTimeMillis()) throw new BusinessException(ErrorCode.ACCESS_TOKEN_EXPIRED);
        return new AuthenticatedUser(user, current);
    }

    public AuthenticatedUser recordActivity(AuthenticatedUser authenticated) {
        var current=sessions.touch(authenticated.user().getId(),authenticated.session(),jwt.getSessionIdleSeconds());
        if (current==null) throw new BusinessException(ErrorCode.UNAUTHORIZED);
        return new AuthenticatedUser(authenticated.user(),current);
    }
}

