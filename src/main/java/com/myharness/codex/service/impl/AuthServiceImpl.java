package com.myharness.codex.service.impl;

import com.myharness.codex.entity.dto.LoginDTO;
import com.myharness.codex.entity.enums.ErrorCode;
import com.myharness.codex.entity.enums.UserStatus;
import com.myharness.codex.entity.po.SysUserPO;
import com.myharness.codex.entity.vo.LoginVO;
import com.myharness.codex.entity.vo.UserProfileVO;
import com.myharness.codex.entity.vo.SessionTokenVO;
import com.myharness.codex.entity.vo.SessionActivityVO;
import com.myharness.codex.security.RefreshCredentials;
import com.myharness.codex.exception.BusinessException;
import com.myharness.codex.mapper.SysUserMapper;
import com.myharness.codex.security.JwtTokenService;
import com.myharness.codex.security.RedisLoginSessionStore;
import com.myharness.codex.security.UserContext;
import com.myharness.codex.security.UserPrincipal;
import com.myharness.codex.service.AuthService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import com.myharness.codex.websocket.ClientEventWebSocketHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Service
public class AuthServiceImpl implements AuthService {
    private static final Logger LOG = LoggerFactory.getLogger(AuthServiceImpl.class);

    private final SysUserMapper sysUserMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenService jwtTokenService;
    private final com.myharness.codex.security.AuthorizationService authorization;
    private final com.myharness.codex.security.LoginAttemptLimiter limiter;
    private final RedisLoginSessionStore sessions;
    private final ClientEventWebSocketHandler sockets;

    public AuthServiceImpl(SysUserMapper sysUserMapper,
                           PasswordEncoder passwordEncoder,
                           JwtTokenService jwtTokenService, com.myharness.codex.security.AuthorizationService authorization,
                           com.myharness.codex.security.LoginAttemptLimiter limiter,
                           RedisLoginSessionStore sessions, ClientEventWebSocketHandler sockets) {
        this.sysUserMapper = sysUserMapper;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenService = jwtTokenService;
        this.authorization=authorization; this.limiter=limiter;
        this.sessions=sessions; this.sockets=sockets;
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public LoginVO login(LoginDTO dto) {
        String email = com.myharness.codex.security.LoginEmail.normalize(dto.getEmail());
        limiter.attempt(email.toLowerCase(java.util.Locale.ROOT));
        SysUserPO user = sysUserMapper.lockByEmail(email);
        if (user == null || !user.isActivated() || !passwordEncoder.matches(dto.getPassword(), user.getPasswordHash())) {
            throw new BusinessException(ErrorCode.INVALID_CREDENTIALS);
        }
        if (!UserStatus.ENABLED.name().equals(user.getStatus())) {
            throw new BusinessException(ErrorCode.USER_DISABLED);
        }

        UserProfileVO profile = toProfile(user);
        var previous = sessions.read(user.getId());
        Instant issuedAt = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        long version = Math.addExact(user.getTokenVersion(), 1);
        String refreshCredential=RefreshCredentials.create();
        long absolute=issuedAt.plusSeconds(jwtTokenService.getSessionAbsoluteSeconds()).getEpochSecond();
        var next = new RedisLoginSessionStore.Session(version, UUID.randomUUID().toString(),
                absolute,Math.min(absolute,issuedAt.plusSeconds(jwtTokenService.getSessionIdleSeconds()).getEpochSecond()),
                1,RefreshCredentials.digest(refreshCredential),"",0);
        SessionTokenVO credentials=credentials(user.getId(),next,refreshCredential,issuedAt);
        if (sysUserMapper.advanceLogin(user.getId(), user.getTokenVersion(),
                LocalDateTime.ofInstant(issuedAt, ZoneOffset.UTC)) != 1) {
            throw new BusinessException(ErrorCode.CONFLICT, "登录状态已变化，请重新登录");
        }
        // Register before the Redis command: a timed-out write may still have succeeded.
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                limiter.success(email);
                sockets.disconnectBeforeVersion(user.getId(), version, true);
            }
            @Override public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) {
                    try { sessions.revoke(user.getId(), next); }
                    catch (RuntimeException ignored) {
                        // SQL's version still rejects this uncommitted session. Never restore the old sid.
                        LOG.warn("Could not clear an uncommitted login session for user {}", user.getId());
                    }
                }
            }
        });
        if (!sessions.replace(user.getId(), previous, next)) {
            throw new BusinessException(ErrorCode.CONFLICT, "登录状态已变化，请重新登录");
        }
        return new LoginVO(credentials,profile);
    }

    @Override
    @Transactional(isolation=Isolation.READ_COMMITTED,noRollbackFor=RefreshReplayException.class)
    public SessionTokenVO refresh(String sid,String credential) {
        String digest=RefreshCredentials.digest(credential);
        Long userId=sessions.userIdForSid(sid);
        if (userId==null) throw new BusinessException(ErrorCode.UNAUTHORIZED);
        SysUserPO user=sysUserMapper.lockById(userId);
        var current=sessions.read(userId);
        requireLiveSession(user,current,sid);
        String nextCredential=RefreshCredentials.create();
        var result=sessions.rotate(userId,current,digest,RefreshCredentials.digest(nextCredential),jwtTokenService.getRefreshReplayGraceSeconds());
        if (result.status()==RedisLoginSessionStore.RotationStatus.CONFLICT) throw new BusinessException(ErrorCode.REFRESH_CONFLICT);
        if (result.status()==RedisLoginSessionStore.RotationStatus.REPLAYED) {
            if (sysUserMapper.revokeVersion(userId,user.getTokenVersion())!=1) throw new BusinessException(ErrorCode.CONFLICT);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { sockets.disconnectBeforeVersion(userId,user.getTokenVersion()+1,false); }
            });
            throw new RefreshReplayException();
        }
        if (result.status()!=RedisLoginSessionStore.RotationStatus.ROTATED) throw new BusinessException(ErrorCode.UNAUTHORIZED);
        return credentials(userId,result.session(),nextCredential,Instant.now().truncatedTo(ChronoUnit.SECONDS));
    }

    @Override
    public SessionActivityVO activity() {
        var principal=UserContext.requireCurrentUser();
        var current=sessions.read(principal.getId());
        if (principal.getLoginSession()==null || !principal.getLoginSession().sameLogin(current)) throw new BusinessException(ErrorCode.UNAUTHORIZED);
        var touched=sessions.touch(principal.getId(),current,jwtTokenService.getSessionIdleSeconds());
        if (touched==null) throw new BusinessException(ErrorCode.UNAUTHORIZED);
        return new SessionActivityVO(touched.sid(),touched.idleExpiresAtEpochSeconds(),touched.expiresAtEpochSeconds());
    }

    private void requireLiveSession(SysUserPO user,RedisLoginSessionStore.Session current,String sid) {
        if (user==null || !user.isActivated() || !UserStatus.ENABLED.name().equals(user.getStatus()) || current==null
                || current.version()!=user.getTokenVersion() || !current.active(Instant.now().getEpochSecond()))
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        if (!current.sid().equals(sid)) throw new BusinessException(ErrorCode.SESSION_REPLACED);
    }

    private SessionTokenVO credentials(Long userId,RedisLoginSessionStore.Session session,String refreshCredential,Instant issuedAt) {
        long accessExpiry=Math.min(issuedAt.plusSeconds(jwtTokenService.getExpiresInSeconds()).getEpochSecond(),session.expiresAtEpochSeconds());
        if (!session.active(Instant.now().getEpochSecond()) || accessExpiry<=issuedAt.getEpochSecond())
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        String access=jwtTokenService.createToken(userId,session.version(),session.sid(),issuedAt,Instant.ofEpochSecond(session.expiresAtEpochSeconds()));
        return new SessionTokenVO(access,accessExpiry-issuedAt.getEpochSecond(),accessExpiry,session.sid(),session.expiresAtEpochSeconds(),
                session.idleExpiresAtEpochSeconds(),jwtTokenService.getRefreshBeforeSeconds(),session.credentialGeneration(),refreshCredential);
    }

    private static final class RefreshReplayException extends BusinessException {
        private RefreshReplayException() { super(ErrorCode.UNAUTHORIZED,"刷新凭证已被使用，请重新登录"); }
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
