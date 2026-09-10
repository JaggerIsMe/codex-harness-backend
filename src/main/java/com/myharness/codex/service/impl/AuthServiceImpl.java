package com.myharness.codex.service.impl;

import com.myharness.codex.entity.dto.LoginDTO;
import com.myharness.codex.entity.enums.ErrorCode;
import com.myharness.codex.entity.enums.UserStatus;
import com.myharness.codex.entity.po.SysUserPO;
import com.myharness.codex.entity.vo.LoginVO;
import com.myharness.codex.entity.vo.UserProfileVO;
import com.myharness.codex.exception.BusinessException;
import com.myharness.codex.mapper.SysUserMapper;
import com.myharness.codex.security.JwtTokenService;
import com.myharness.codex.security.UserContext;
import com.myharness.codex.security.UserPrincipal;
import com.myharness.codex.service.AuthService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

@Service
public class AuthServiceImpl implements AuthService {

    private final SysUserMapper sysUserMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenService jwtTokenService;
    private final com.myharness.codex.security.AuthorizationService authorization;
    private final com.myharness.codex.security.LoginAttemptLimiter limiter;

    public AuthServiceImpl(SysUserMapper sysUserMapper,
                           PasswordEncoder passwordEncoder,
                           JwtTokenService jwtTokenService, com.myharness.codex.security.AuthorizationService authorization,
                           com.myharness.codex.security.LoginAttemptLimiter limiter) {
        this.sysUserMapper = sysUserMapper;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenService = jwtTokenService;
        this.authorization=authorization; this.limiter=limiter;
    }

    @Override
    @Transactional
    public LoginVO login(LoginDTO dto) {
        String email = com.myharness.codex.security.LoginEmail.normalize(dto.getEmail());
        limiter.attempt(email.toLowerCase(java.util.Locale.ROOT));
        SysUserPO user = sysUserMapper.selectByEmail(email);
        if (user == null || !user.isActivated() || !passwordEncoder.matches(dto.getPassword(), user.getPasswordHash())) {
            throw new BusinessException(ErrorCode.INVALID_CREDENTIALS);
        }
        if (!UserStatus.ENABLED.name().equals(user.getStatus())) {
            throw new BusinessException(ErrorCode.USER_DISABLED);
        }

        sysUserMapper.updateLastLoginAt(user.getId(), LocalDateTime.now(ZoneOffset.UTC));
        limiter.success(email.toLowerCase(java.util.Locale.ROOT));
        UserProfileVO profile = toProfile(user);
        String accessToken = jwtTokenService.createToken(user.getId(), user.getTokenVersion());
        return new LoginVO(accessToken, jwtTokenService.getExpiresInSeconds(), profile);
    }

    @Override
    public UserProfileVO getCurrentUser() {
        UserPrincipal principal = UserContext.requireCurrentUser();
        return toProfile(authorization.requireEnabled(principal.getId()));
    }

    private UserProfileVO toProfile(SysUserPO user) {
        return new UserProfileVO(user.getId(), user.getEmail(), user.getDisplayName())
                .withActivated(user.isActivated())
                .withAccess(authorization.roles(user.getId()),authorization.permissions(user.getId()),user.isMustChangePassword());
    }
}
