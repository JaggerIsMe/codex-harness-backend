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
        authService = new AuthServiceImpl(sysUserMapper, passwordEncoder, jwtTokenService,
                org.mockito.Mockito.mock(com.myharness.codex.security.AuthorizationService.class),
                new com.myharness.codex.security.LoginAttemptLimiter());
    }

    @Test
    void shouldLoginEnabledUserAndUpdateLastLoginTime() {
        SysUserPO user = enabledUser();
        when(sysUserMapper.selectByEmail("admin@example.test")).thenReturn(user);

        LoginVO result = authService.login(loginDto("admin@example.test", "secret"));

        assertNotNull(result.getAccessToken());
        assertEquals(Long.valueOf(1L), jwtTokenService.parseUserId(result.getAccessToken()));
        assertEquals("admin@example.test", result.getUser().getEmail());
        assertEquals(7200L, result.getExpiresInSeconds());
        verify(sysUserMapper).updateLastLoginAt(org.mockito.ArgumentMatchers.eq(1L), any());
    }

    @Test
    void shouldRejectIncorrectPasswordWithoutRevealingEmailExistence() {
        SysUserPO user = enabledUser();
        when(sysUserMapper.selectByEmail("admin@example.test")).thenReturn(user);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> authService.login(loginDto("admin@example.test", "incorrect")));

        assertEquals(ErrorCode.INVALID_CREDENTIALS, exception.getErrorCode());
    }

    @Test
    void shouldRejectDisabledUser() {
        SysUserPO user = enabledUser();
        user.setStatus(UserStatus.DISABLED.name());
        when(sysUserMapper.selectByEmail("admin@example.test")).thenReturn(user);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> authService.login(loginDto("admin@example.test", "secret")));

        assertEquals(ErrorCode.USER_DISABLED, exception.getErrorCode());
    }

    private SysUserPO enabledUser() {
        SysUserPO user = new SysUserPO();
        user.setId(1L);
        user.setEmail("admin@example.test");
        user.setDisplayName("Administrator");
        user.setPasswordHash(passwordEncoder.encode("secret"));
        user.setStatus(UserStatus.ENABLED.name());
        user.setActivatedAt(java.time.LocalDateTime.of(2026, 9, 10, 0, 0));
        user.setEmailVerifiedAt(user.getActivatedAt());
        return user;
    }

    @Test void pendingAccountCannotLoginEvenWithAStoredPassword() {
        SysUserPO user = enabledUser();
        user.setActivatedAt(null);
        when(sysUserMapper.selectByEmail("admin@example.test")).thenReturn(user);
        BusinessException exception = assertThrows(BusinessException.class,
                () -> authService.login(loginDto("admin@example.test", "secret")));
        assertEquals(ErrorCode.INVALID_CREDENTIALS, exception.getErrorCode());
        org.mockito.Mockito.verify(sysUserMapper, org.mockito.Mockito.never()).updateLastLoginAt(any(), any());
    }

    @Test void normalizesEmailAndUsesStableIdentityInToken() {
        when(sysUserMapper.selectByEmail("admin@example.test")).thenReturn(enabledUser());
        LoginVO result = authService.login(loginDto("  ADMIN@EXAMPLE.TEST  ", "secret"));
        var claims = jwtTokenService.parseClaims(result.getAccessToken());
        assertEquals("1", claims.getSubject());
        org.junit.jupiter.api.Assertions.assertFalse(claims.containsKey("username"));
        org.junit.jupiter.api.Assertions.assertFalse(claims.containsKey("email"));
        org.junit.jupiter.api.Assertions.assertTrue(result.getUser().isActivated());
    }

    private LoginDTO loginDto(String email, String password) {
        LoginDTO dto = new LoginDTO();
        dto.setEmail(email);
        dto.setPassword(password);
        return dto;
    }
}
