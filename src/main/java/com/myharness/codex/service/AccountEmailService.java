package com.myharness.codex.service;

import com.myharness.codex.config.AccountMailProperties;
import com.myharness.codex.entity.enums.ErrorCode;
import com.myharness.codex.entity.po.AccountEmailChallengePO;
import com.myharness.codex.entity.po.SysUserPO;
import com.myharness.codex.entity.po.SystemInitializationPO;
import com.myharness.codex.entity.vo.ActivationValidationVO;
import com.myharness.codex.entity.vo.SendCodeVO;
import com.myharness.codex.exception.BusinessException;
import com.myharness.codex.mapper.AccountEmailChallengeMapper;
import com.myharness.codex.mapper.RbacMapper;
import com.myharness.codex.mapper.SysUserMapper;
import com.myharness.codex.mapper.SystemInitializationMapper;
import com.myharness.codex.security.LoginEmail;
import com.myharness.codex.security.PasswordPolicy;
import com.myharness.codex.service.mail.AccountMailCrypto;
import com.myharness.codex.service.mail.AccountMailRateLimiter;
import com.myharness.codex.service.mail.AccountMailService;
import com.myharness.codex.websocket.ClientEventWebSocketHandler;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Owns account state changes; SMTP is performed exclusively by the committed mail task worker. */
@Service
public class AccountEmailService {
    public static final String ACTIVATION = "ACTIVATION";
    public static final String PASSWORD_RESET = "PASSWORD_RESET";
    private static final String BOOTSTRAP_KEY = "BOOTSTRAP_ADMIN";
    private static final Set<String> USER_PERMISSIONS = Set.of("workspace:use", "project:create", "project:read",
            "project:update", "project:delete", "conversation:create", "conversation:read", "conversation:update",
            "conversation:delete", "turn:start", "turn:interrupt", "approval:decide", "expert:read", "expert:use");
    private static final Set<String> ADMIN_PERMISSIONS = Set.of("system:user:manage", "device:manage", "skill:manage",
            "expert:manage", "mcp:manage", "model:manage");

    private final SysUserMapper users;
    private final RbacMapper rbac;
    private final AccountEmailChallengeMapper challenges;
    private final SystemInitializationMapper initialization;
    private final AccountMailService mail;
    private final AccountMailCrypto crypto;
    private final AccountMailRateLimiter limiter;
    private final AccountMailProperties properties;
    private final PasswordEncoder passwords;
    private final ClientEventWebSocketHandler sockets;
    private final TransactionTemplate resetTransaction;
    private final Clock clock;

    @Autowired
    public AccountEmailService(SysUserMapper users, RbacMapper rbac, AccountEmailChallengeMapper challenges,
                               SystemInitializationMapper initialization, AccountMailService mail, AccountMailCrypto crypto,
                               AccountMailRateLimiter limiter, AccountMailProperties properties, PasswordEncoder passwords,
                               ClientEventWebSocketHandler sockets, PlatformTransactionManager transactions) {
        this(users, rbac, challenges, initialization, mail, crypto, limiter, properties, passwords, sockets, transactions, Clock.systemUTC());
    }

    AccountEmailService(SysUserMapper users, RbacMapper rbac, AccountEmailChallengeMapper challenges,
                        SystemInitializationMapper initialization, AccountMailService mail, AccountMailCrypto crypto,
                        AccountMailRateLimiter limiter, AccountMailProperties properties, PasswordEncoder passwords,
                        ClientEventWebSocketHandler sockets, PlatformTransactionManager transactions, Clock clock) {
        this.users = users;
        this.rbac = rbac;
        this.challenges = challenges;
        this.initialization = initialization;
        this.mail = mail;
        this.crypto = crypto;
        this.limiter = limiter;
        this.properties = properties;
        this.passwords = passwords;
        this.sockets = sockets;
        this.clock = clock;
        resetTransaction = new TransactionTemplate(transactions);
        resetTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        resetTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Internal provisioning entry point. The administrative caller must authorize and lock administration first. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public SysUserPO createInvitedUser(String email, String role, String displayName, Long operatorId) {
        String normalized = LoginEmail.normalize(email);
        if (!Set.of("SYS_ADMIN", "USER").contains(role)) throw new BusinessException(ErrorCode.INVALID_REQUEST, "角色不存在");
        mail.requireSendingConfigured();
        if (users.selectByEmail(normalized) != null) throw new BusinessException(ErrorCode.CONFLICT, "邮箱已存在");
        limiter.checkSend(normalized, ACTIVATION, "administrator");
        SysUserPO user = new SysUserPO();
        user.setEmail(normalized);
        user.setDisplayName(displayName(displayName, normalized.substring(0, normalized.indexOf('@'))));
        user.setStatus("ENABLED");
        user.setMustChangePassword(false);
        try {
            if (users.insert(user) != 1) throw new IllegalStateException("Failed to create invited user");
        } catch (DuplicateKeyException ex) {
            throw new BusinessException(ErrorCode.CONFLICT, "邮箱已存在");
        }
        if (rbac.assignRole(user.getId(), role) != 1) throw seedMissing();
        issueChallenge(user, ACTIVATION);
        audit(operatorId == null ? "SYSTEM" : "USER", operatorId, "USER_INVITE", user.getId());
        return user;
    }

    /** The permanent marker, rather than available-administrator count, decides whether initialization occurred. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void bootstrap(String email, String displayName) {
        if (rbac.lockAdministratorRole() == null) throw seedMissing();
        if (initialization.lock(BOOTSTRAP_KEY) != null) return;
        Set<String> admin = Set.copyOf(initialization.rolePermissions("SYS_ADMIN"));
        Set<String> ordinary = Set.copyOf(initialization.rolePermissions("USER"));
        if (!admin.containsAll(USER_PERMISSIONS) || !admin.containsAll(ADMIN_PERMISSIONS)
                || !ordinary.containsAll(USER_PERMISSIONS)) throw seedMissing();
        if (initialization.userCount() != 0) {
            throw new IllegalStateException("Bootstrap initialization record is missing but users already exist; inspect the database before starting");
        }
        final String normalized;
        try { normalized = LoginEmail.normalize(email); }
        catch (BusinessException ex) {
            throw new IllegalStateException("Bootstrap admin requires a valid HARNESS_BOOTSTRAP_ADMIN_EMAIL");
        }
        SysUserPO user = createInvitedUser(normalized, "SYS_ADMIN", displayName, null);
        SystemInitializationPO marker = new SystemInitializationPO();
        marker.setInitializationKey(BOOTSTRAP_KEY);
        marker.setUserId(user.getId());
        marker.setEmail(user.getEmail());
        if (initialization.insert(marker) != 1) throw new IllegalStateException("Failed to persist bootstrap initialization");
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void resendActivation(Long userId) {
        mail.requireSendingConfigured();
        SysUserPO user = rbac.lockUser(userId);
        if (!canActivate(user)) throw new BusinessException(ErrorCode.CONFLICT, "只有启用的待激活账号可以重发激活邮件");
        limiter.checkSend(user.getEmail(), ACTIVATION, "administrator");
        issueChallenge(user, ACTIVATION);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public SendCodeVO publicResendActivation(String email, String sourceIp) {
        return requestChallenge(email, sourceIp, ACTIVATION);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public SendCodeVO requestPasswordReset(String email, String sourceIp) {
        return requestChallenge(email, sourceIp, PASSWORD_RESET);
    }

    private SendCodeVO requestChallenge(String email, String sourceIp, String purpose) {
        String normalized = LoginEmail.normalize(email);
        mail.requireSendingConfigured();
        // Consume the same budgets before looking up known, unknown, disabled, or ineligible accounts.
        limiter.checkSend(normalized, purpose, sourceIp);
        SysUserPO found = users.selectByEmail(normalized);
        if (found != null) {
            SysUserPO user = rbac.lockUser(found.getId());
            if (Objects.equals(normalized, user == null ? null : user.getEmail())
                    && (ACTIVATION.equals(purpose) ? canActivate(user) : canReset(user))) {
                issueChallenge(user, purpose);
                audit("ANONYMOUS", null, ACTIVATION.equals(purpose) ? "ACTIVATION_EMAIL_REQUEST" : "PASSWORD_RECOVERY_REQUEST", user.getId());
            }
        }
        return new SendCodeVO(properties.getResendCooldownSeconds());
    }

    public ActivationValidationVO validateActivation(String token) {
        AccountEmailChallengePO challenge = findActivation(token);
        SysUserPO user = users.selectById(challenge.getUserId());
        requireActivationChallenge(challenge, user, now());
        return new ActivationValidationVO(maskEmail(challenge.getEmail()), user.getDisplayName(), challenge.getExpiresAt());
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void activate(String token, String newPassword, String displayName) {
        PasswordPolicy.validate(newPassword);
        AccountEmailChallengePO found = findActivation(token);
        SysUserPO user = rbac.lockUser(found.getUserId());
        AccountEmailChallengePO challenge = challenges.lockById(found.getId());
        LocalDateTime now = now();
        requireActivationChallenge(challenge, user, now);
        String chosenName = displayName(displayName, user.getDisplayName());
        String hash = passwords.encode(newPassword);
        now = now();
        if (!challenge.getExpiresAt().isAfter(now)) throw new BusinessException(ErrorCode.EMAIL_VERIFICATION_EXPIRED);
        if (challenges.consume(challenge.getId(), now, properties.getMaxVerificationFailures()) != 1) throw invalid();
        if (challenges.activateUser(user.getId(), hash, chosenName, now) != 1) throw invalid();
        revokeChallenges(user.getId());
        initialization.complete(user.getId(), now);
        audit("ANONYMOUS", null, "USER_ACTIVATE", user.getId());
        disconnectAfterCommit(user.getId(), user.getTokenVersion() + 1);
    }

    /** Verification errors are thrown after commit, so incorrect attempts cannot roll back their own counter. */
    public void resetPassword(String email, String code, String newPassword, String sourceIp) {
        String normalized = LoginEmail.normalize(email);
        PasswordPolicy.validate(newPassword);
        limiter.checkVerify(normalized, PASSWORD_RESET, sourceIp);
        ErrorCode failure = resetTransaction.execute(status -> resetLocked(normalized, code, newPassword));
        if (failure != null) throw new BusinessException(failure, "验证码错误或已失效");
    }

    private ErrorCode resetLocked(String email, String code, String newPassword) {
        SysUserPO found = users.selectByEmail(email);
        if (found == null) return ErrorCode.EMAIL_VERIFICATION_INVALID;
        SysUserPO user = rbac.lockUser(found.getId());
        if (!canReset(user) || !email.equals(user.getEmail())) return ErrorCode.EMAIL_VERIFICATION_INVALID;
        AccountEmailChallengePO challenge = challenges.lockLatest(user.getId(), PASSWORD_RESET);
        LocalDateTime now = now();
        if (!active(challenge, now) || !email.equals(challenge.getEmail())) return ErrorCode.EMAIL_VERIFICATION_INVALID;
        boolean wellFormed = code != null && code.matches("[0-9]{6}");
        String supplied = wellFormed ? crypto.resetDigest(user.getId(), email, challenge.getGeneration(), code) : null;
        if (!wellFormed || !crypto.matches(challenge.getDigest(), supplied)) {
            if (challenges.recordFailure(challenge.getId(), properties.getMaxVerificationFailures(), now) != 1) throw invalid();
            if (challenge.getFailedAttempts() + 1 >= properties.getMaxVerificationFailures()) mail.revokeChallengesTasks(user.getId(), PASSWORD_RESET);
            return ErrorCode.EMAIL_VERIFICATION_INVALID;
        }
        if (passwords.matches(newPassword, user.getPasswordHash())) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "新密码不能与原密码相同");
        }
        String hash = passwords.encode(newPassword);
        now = now();
        if (!active(challenge, now)) return ErrorCode.EMAIL_VERIFICATION_INVALID;
        if (challenges.consume(challenge.getId(), now, properties.getMaxVerificationFailures()) != 1) throw invalid();
        if (rbac.changePassword(user.getId(), hash, false) != 1) throw new IllegalStateException("Failed to update account password");
        revokeChallenges(user.getId());
        mail.enqueuePasswordChanged(user, "password-reset:" + challenge.getId());
        audit("ANONYMOUS", null, "USER_PASSWORD_RECOVERY", user.getId());
        disconnectAfterCommit(user.getId(), user.getTokenVersion() + 1);
        return null;
    }

    /** Called inside the same transaction as disable or any password change, while holding the user lock. */
    @Transactional
    public void revokeChallenges(Long userId) {
        challenges.revoke(userId, null, now());
        mail.revokeChallengesTasks(userId);
    }

    private void issueChallenge(SysUserPO user, String purpose) {
        LocalDateTime now = now();
        challenges.revoke(user.getId(), purpose, now);
        mail.revokeChallengesTasks(user.getId(), purpose);
        AccountEmailChallengePO challenge = new AccountEmailChallengePO();
        challenge.setUserId(user.getId());
        challenge.setEmail(user.getEmail());
        challenge.setPurpose(purpose);
        challenge.setGeneration(UUID.randomUUID().toString());
        challenge.setStatus("ACTIVE");
        String secret;
        if (ACTIVATION.equals(purpose)) {
            secret = crypto.newActivationToken();
            challenge.setDigest(crypto.activationDigest(secret));
            challenge.setExpiresAt(now.plusHours(properties.getActivationTtlHours()));
        } else {
            secret = crypto.newResetCode();
            challenge.setDigest(crypto.resetDigest(user.getId(), user.getEmail(), challenge.getGeneration(), secret));
            challenge.setExpiresAt(now.plusMinutes(properties.getResetTtlMinutes()));
        }
        if (challenges.insert(challenge) != 1) throw new IllegalStateException("Failed to persist account verification");
        if (ACTIVATION.equals(purpose)) mail.enqueueActivation(user, challenge.getId(), secret, challenge.getExpiresAt());
        else mail.enqueuePasswordReset(user, challenge.getId(), secret, challenge.getExpiresAt());
    }

    private AccountEmailChallengePO findActivation(String token) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) throw invalid();
        AccountEmailChallengePO challenge = challenges.findActivation(crypto.activationDigest(token));
        if (challenge == null) throw invalid();
        return challenge;
    }

    private void requireActivationChallenge(AccountEmailChallengePO challenge, SysUserPO user, LocalDateTime now) {
        if (challenge == null || !ACTIVATION.equals(challenge.getPurpose())) throw invalid();
        if ("USED".equals(challenge.getStatus())) throw new BusinessException(ErrorCode.EMAIL_VERIFICATION_USED);
        if ("EXPIRED".equals(challenge.getStatus())) throw new BusinessException(ErrorCode.EMAIL_VERIFICATION_EXPIRED);
        if (!"ACTIVE".equals(challenge.getStatus())) throw invalid();
        if (!challenge.getExpiresAt().isAfter(now)) throw new BusinessException(ErrorCode.EMAIL_VERIFICATION_EXPIRED);
        AccountEmailChallengePO latest = challenges.latest(challenge.getUserId(), ACTIVATION);
        if (!canActivate(user) || !Objects.equals(challenge.getUserId(), user.getId())
                || !Objects.equals(challenge.getEmail(), user.getEmail()) || latest == null
                || !Objects.equals(challenge.getId(), latest.getId())) throw invalid();
    }

    private boolean active(AccountEmailChallengePO challenge, LocalDateTime now) {
        return challenge != null && "ACTIVE".equals(challenge.getStatus()) && challenge.getExpiresAt().isAfter(now)
                && challenge.getFailedAttempts() < properties.getMaxVerificationFailures();
    }

    private boolean canActivate(SysUserPO user) {
        return user != null && "ENABLED".equals(user.getStatus()) && user.getActivatedAt() == null
                && user.getEmailVerifiedAt() == null && user.getPasswordHash() == null;
    }

    private boolean canReset(SysUserPO user) {
        return user != null && "ENABLED".equals(user.getStatus()) && user.isActivated();
    }

    private String displayName(String supplied, String fallback) {
        String name = supplied == null ? fallback : supplied.trim();
        if (supplied == null && name.length() > 128) name = name.substring(0, 128);
        if (name.isBlank() || name.length() > 128) throw new BusinessException(ErrorCode.INVALID_REQUEST, "显示名称须为1–128个字符");
        return name;
    }

    private String maskEmail(String email) {
        int at = email.indexOf('@');
        return email.substring(0, 1) + "***" + email.substring(at);
    }

    private LocalDateTime now() { return LocalDateTime.now(clock); }
    private BusinessException invalid() { return new BusinessException(ErrorCode.EMAIL_VERIFICATION_INVALID); }
    private IllegalStateException seedMissing() {
        return new IllegalStateException("RBAC SYS_ADMIN/USER seed is missing or incomplete; run db/seed-rbac.sql before starting the server");
    }
    private void audit(String type, Long operator, String action, Long userId) {
        challenges.audit(type, operator, action, String.valueOf(userId));
    }
    private void disconnectAfterCommit(Long userId, long version) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { sockets.disconnectBeforeVersion(userId, version, false); }
        });
    }
}
