package com.myharness.codex.service.impl;

import com.myharness.codex.config.SecurityProperties;
import com.myharness.codex.entity.dto.LoginDTO;
import com.myharness.codex.entity.enums.ErrorCode;
import com.myharness.codex.entity.enums.UserStatus;
import com.myharness.codex.entity.po.SysUserPO;
import com.myharness.codex.entity.vo.LoginVO;
import com.myharness.codex.exception.BusinessException;
import com.myharness.codex.mapper.SysUserMapper;
import com.myharness.codex.security.JwtTokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceImplTest {

    @Mock
    private SysUserMapper sysUserMapper;

    private PasswordEncoder passwordEncoder;
    private JwtTokenService jwtTokenService;
    private AuthServiceImpl authService;

    @BeforeEach
    void setUp() {
        passwordEncoder = new BCryptPasswordEncoder(4);
        SecurityProperties properties = new SecurityProperties();
        properties.setJwtSecret("test-secret-with-at-least-32-bytes-long");
        properties.setJwtExpireMinutes(120L);
        jwtTokenService = new JwtTokenService(properties);
        authService = new AuthServiceImpl(sysUserMapper, passwordEncoder, jwtTokenService);
    }

    @Test
    void shouldLoginEnabledUserAndUpdateLastLoginTime() {
        SysUserPO user = enabledUser();
        when(sysUserMapper.selectByUsername("admin")).thenReturn(user);

        LoginVO result = authService.login(loginDto("admin", "secret"));

        assertNotNull(result.getAccessToken());
        assertEquals(Long.valueOf(1L), jwtTokenService.parseUserId(result.getAccessToken()));
        assertEquals("admin", result.getUser().getUsername());
        assertEquals(7200L, result.getExpiresInSeconds());
        verify(sysUserMapper).updateLastLoginAt(org.mockito.ArgumentMatchers.eq(1L), any());
    }

    @Test
    void shouldRejectIncorrectPasswordWithoutRevealingUsernameExistence() {
        SysUserPO user = enabledUser();
        when(sysUserMapper.selectByUsername("admin")).thenReturn(user);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> authService.login(loginDto("admin", "incorrect")));

        assertEquals(ErrorCode.INVALID_CREDENTIALS, exception.getErrorCode());
    }

    @Test
    void shouldRejectDisabledUser() {
        SysUserPO user = enabledUser();
        user.setStatus(UserStatus.DISABLED.name());
        when(sysUserMapper.selectByUsername("admin")).thenReturn(user);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> authService.login(loginDto("admin", "secret")));

        assertEquals(ErrorCode.USER_DISABLED, exception.getErrorCode());
    }

    private SysUserPO enabledUser() {
        SysUserPO user = new SysUserPO();
        user.setId(1L);
        user.setUsername("admin");
        user.setDisplayName("Administrator");
        user.setPasswordHash(passwordEncoder.encode("secret"));
        user.setStatus(UserStatus.ENABLED.name());
        return user;
    }

    private LoginDTO loginDto(String username, String password) {
        LoginDTO dto = new LoginDTO();
        dto.setUsername(username);
        dto.setPassword(password);
        return dto;
    }
}
