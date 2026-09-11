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
import com.myharness.codex.security.RedisLoginSessionStore;
import com.myharness.codex.websocket.ClientEventWebSocketHandler;
import org.junit.jupiter.api.AfterEach;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceImplTest {

    @Mock
    private SysUserMapper sysUserMapper;
    @Mock private RedisLoginSessionStore sessions;
    @Mock private ClientEventWebSocketHandler sockets;

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
                new com.myharness.codex.security.LoginAttemptLimiter(), sessions, sockets);
        TransactionSynchronizationManager.initSynchronization();
        lenient().when(sysUserMapper.advanceLogin(any(), anyLong(), any())).thenReturn(1);
        lenient().when(sessions.replace(any(), any(), any())).thenReturn(true);
    }

    @AfterEach void cleanup() { TransactionSynchronizationManager.clearSynchronization(); }

    @Test
    void shouldLoginEnabledUserAndUpdateLastLoginTime() {
        SysUserPO user = enabledUser();
        when(sysUserMapper.lockByEmail("admin@example.test")).thenReturn(user);

        LoginVO result = authService.login(loginDto("admin@example.test", "secret"));

        assertNotNull(result.getAccessToken());
        assertEquals(Long.valueOf(1L), jwtTokenService.parseUserId(result.getAccessToken()));
        assertEquals("admin@example.test", result.getUser().getEmail());
        assertEquals(7200L, result.getExpiresInSeconds());
        verify(sysUserMapper).advanceLogin(eq(1L), eq(0L), any());
        var session=jwtTokenService.session(jwtTokenService.parseClaims(result.getAccessToken()));
        assertEquals(1L,session.version());
        var captured=org.mockito.ArgumentCaptor.forClass(RedisLoginSessionStore.Session.class);
        verify(sessions).replace(eq(1L),isNull(),captured.capture());
        assertTrue(captured.getValue().sameLogin(session));
        assertEquals(result.getSessionExpiresAt(),captured.getValue().expiresAtEpochSeconds());
        assertEquals(1,captured.getValue().credentialGeneration());
        verifyNoInteractions(sockets);
        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        verify(sockets).disconnectBeforeVersion(1L,1L,true);
    }

    @Test
    void shouldRejectIncorrectPasswordWithoutRevealingEmailExistence() {
        SysUserPO user = enabledUser();
        when(sysUserMapper.lockByEmail("admin@example.test")).thenReturn(user);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> authService.login(loginDto("admin@example.test", "incorrect")));

        assertEquals(ErrorCode.INVALID_CREDENTIALS, exception.getErrorCode());
        verifyNoInteractions(sessions,sockets);
    }

    @Test
    void shouldRejectDisabledUser() {
        SysUserPO user = enabledUser();
        user.setStatus(UserStatus.DISABLED.name());
        when(sysUserMapper.lockByEmail("admin@example.test")).thenReturn(user);

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
        when(sysUserMapper.lockByEmail("admin@example.test")).thenReturn(user);
        BusinessException exception = assertThrows(BusinessException.class,
                () -> authService.login(loginDto("admin@example.test", "secret")));
        assertEquals(ErrorCode.INVALID_CREDENTIALS, exception.getErrorCode());
        verify(sysUserMapper, never()).advanceLogin(any(), anyLong(), any());
    }

    @Test void normalizesEmailAndUsesStableIdentityInToken() {
        when(sysUserMapper.lockByEmail("admin@example.test")).thenReturn(enabledUser());
        LoginVO result = authService.login(loginDto("  ADMIN@EXAMPLE.TEST  ", "secret"));
        var claims = jwtTokenService.parseClaims(result.getAccessToken());
        assertEquals("1", claims.getSubject());
        org.junit.jupiter.api.Assertions.assertFalse(claims.containsKey("username"));
        org.junit.jupiter.api.Assertions.assertFalse(claims.containsKey("email"));
        org.junit.jupiter.api.Assertions.assertTrue(result.getUser().isActivated());
    }

    @Test void redisReadFailureDoesNotAdvanceOrIssueALogin() {
        when(sysUserMapper.lockByEmail("admin@example.test")).thenReturn(enabledUser());
        when(sessions.read(1L)).thenThrow(new BusinessException(ErrorCode.SESSION_UNAVAILABLE));
        var error=assertThrows(BusinessException.class,()->authService.login(loginDto("admin@example.test","secret")));
        assertEquals(ErrorCode.SESSION_UNAVAILABLE,error.getErrorCode());
        verify(sysUserMapper,never()).advanceLogin(any(),anyLong(),any());
        verifyNoInteractions(sockets);
    }

    @Test void rollbackAfterUnknownRedisWriteOnlyRevokesItsOwnAttempt() {
        when(sysUserMapper.lockByEmail("admin@example.test")).thenReturn(enabledUser());
        when(sessions.replace(any(),any(),any())).thenThrow(new BusinessException(ErrorCode.SESSION_UNAVAILABLE));
        assertThrows(BusinessException.class,()->authService.login(loginDto("admin@example.test","secret")));
        var attempted=org.mockito.ArgumentCaptor.forClass(RedisLoginSessionStore.Session.class);
        verify(sessions).replace(eq(1L),isNull(),attempted.capture());
        TransactionSynchronizationManager.getSynchronizations().forEach(s->s.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
        verify(sessions).revoke(1L,attempted.getValue());
        verifyNoInteractions(sockets);
    }

    @Test void failedCompareAndSwapDoesNotRetryOrDisconnect() {
        when(sysUserMapper.lockByEmail("admin@example.test")).thenReturn(enabledUser());
        when(sessions.replace(any(),any(),any())).thenReturn(false);
        assertEquals(ErrorCode.CONFLICT,assertThrows(BusinessException.class,
                ()->authService.login(loginDto("admin@example.test","secret"))).getErrorCode());
        verify(sessions,times(1)).replace(any(),any(),any());
        verifyNoInteractions(sockets);
    }

    private LoginDTO loginDto(String email, String password) {
        LoginDTO dto = new LoginDTO();
        dto.setEmail(email);
        dto.setPassword(password);
        return dto;
    }

    @Test void refreshCannotIssueCredentialsIfAbsoluteExpiryIsReachedAfterRotation() {
        String sid=java.util.UUID.randomUUID().toString();
        long now=java.time.Instant.now().getEpochSecond();
        String secret=com.myharness.codex.security.RefreshCredentials.create();
        var current=new RedisLoginSessionStore.Session(0,sid,now+3600,now+3600,1,
                com.myharness.codex.security.RefreshCredentials.digest(secret),"",0);
        var expired=new RedisLoginSessionStore.Session(0,sid,now-1,now-1,2,"a".repeat(64),"b".repeat(64),now+10);
        when(sessions.userIdForSid(sid)).thenReturn(1L);
        when(sysUserMapper.lockById(1L)).thenReturn(enabledUser());
        when(sessions.read(1L)).thenReturn(current);
        when(sessions.rotate(eq(1L),eq(current),any(),any(),anyLong()))
                .thenReturn(new RedisLoginSessionStore.Rotation(RedisLoginSessionStore.RotationStatus.ROTATED,expired));
        assertEquals(ErrorCode.UNAUTHORIZED,assertThrows(com.myharness.codex.exception.BusinessException.class,
                ()->authService.refresh(sid,secret)).getErrorCode());
        verify(sysUserMapper,never()).advanceLogin(any(),anyLong(),any());
        verify(sysUserMapper,never()).revokeVersion(any(),anyLong());
    }
}
