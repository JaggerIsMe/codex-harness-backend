package com.myharness.codex.security;

import com.myharness.codex.entity.enums.ErrorCode;
import com.myharness.codex.entity.po.SysUserPO;
import com.myharness.codex.exception.BusinessException;
import com.myharness.codex.mapper.SysUserMapper;
import org.springframework.stereotype.Service;

@Service
public class UserAuthenticationService {
    private final JwtTokenService jwt;
    private final SysUserMapper users;
    public UserAuthenticationService(JwtTokenService jwt,SysUserMapper users) { this.jwt=jwt; this.users=users; }
    public SysUserPO authenticate(String token) {
        var claims=jwt.parseClaims(token);
        SysUserPO user=users.selectById(Long.valueOf(claims.getSubject()));
        Number version=claims.get("version",Number.class);
        if(user==null || !"ENABLED".equals(user.getStatus()) || version==null || version.longValue()!=user.getTokenVersion())
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        return user;
    }
}

