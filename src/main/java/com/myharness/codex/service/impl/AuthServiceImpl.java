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

    public AuthServiceImpl(SysUserMapper sysUserMapper,
                           PasswordEncoder passwordEncoder,
                           JwtTokenService jwtTokenService) {
        this.sysUserMapper = sysUserMapper;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenService = jwtTokenService;
    }

    @Override
    @Transactional
    public LoginVO login(LoginDTO dto) {
        String username = dto.getUsername().trim();
        SysUserPO user = sysUserMapper.selectByUsername(username);
        if (user == null || !passwordEncoder.matches(dto.getPassword(), user.getPasswordHash())) {
            throw new BusinessException(ErrorCode.INVALID_CREDENTIALS);
        }
        if (!UserStatus.ENABLED.name().equals(user.getStatus())) {
            throw new BusinessException(ErrorCode.USER_DISABLED);
        }

        sysUserMapper.updateLastLoginAt(user.getId(), LocalDateTime.now(ZoneOffset.UTC));
        UserProfileVO profile = toProfile(user);
        String accessToken = jwtTokenService.createToken(user.getId(), user.getUsername());
        return new LoginVO(accessToken, jwtTokenService.getExpiresInSeconds(), profile);
    }

    @Override
    public UserProfileVO getCurrentUser() {
        UserPrincipal principal = UserContext.requireCurrentUser();
        return new UserProfileVO(principal.getId(), principal.getUsername(), principal.getDisplayName());
    }

    private UserProfileVO toProfile(SysUserPO user) {
        return new UserProfileVO(user.getId(), user.getUsername(), user.getDisplayName());
    }
}
