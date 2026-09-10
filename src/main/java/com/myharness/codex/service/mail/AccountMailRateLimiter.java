package com.myharness.codex.service.mail;

import com.myharness.codex.config.AccountMailProperties;
import com.myharness.codex.entity.enums.ErrorCode;
import com.myharness.codex.exception.BusinessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class AccountMailRateLimiter {
    // Check every budget before consuming any of them. Rejected attempts cannot extend a cooldown.
    private static final DefaultRedisScript<Long> LIMIT = new DefaultRedisScript<>("""
            local retry = 0
            for i,key in ipairs(KEYS) do
              local count = tonumber(redis.call('GET', key) or '0')
              if count >= tonumber(ARGV[(i-1)*2+1]) then
                retry = math.max(retry, math.max(1, redis.call('TTL', key)))
              end
            end
            if retry > 0 then return retry end
            for i,key in ipairs(KEYS) do
              if redis.call('INCR', key) == 1 then redis.call('EXPIRE', key, ARGV[(i-1)*2+2]) end
            end
            return 0
            """, Long.class);
    private final StringRedisTemplate redis;
    private final AccountMailCrypto crypto;
    private final AccountMailProperties properties;

    public AccountMailRateLimiter(StringRedisTemplate redis, AccountMailCrypto crypto, AccountMailProperties properties) {
        this.redis = redis;
        this.crypto = crypto;
        this.properties = properties;
    }

    public void checkSend(String email, String purpose, String sourceIp) {
        List<String> keys = new ArrayList<>();
        List<String> args = new ArrayList<>();
        add(keys, args, "send:cooldown:" + purpose + ":" + email, 1, properties.getResendCooldownSeconds());
        add(keys, args, "send:email:" + email, properties.getEmailHourlyLimit(), 3600);
        add(keys, args, "send:ip:" + normalizedIp(sourceIp), properties.getIpSendHourlyLimit(), 3600);
        execute(keys, args);
    }

    public void checkVerify(String email, String purpose, String sourceIp) {
        List<String> keys = new ArrayList<>();
        List<String> args = new ArrayList<>();
        add(keys, args, "verify:email:" + email, properties.getEmailVerifyHourlyLimit(), 3600);
        add(keys, args, "verify:ip:" + normalizedIp(sourceIp), properties.getIpVerifyHourlyLimit(), 3600);
        execute(keys, args);
    }

    private String normalizedIp(String value) { return value == null || value.isBlank() ? "unknown" : value; }

    private void add(List<String> keys, List<String> args, String suffix, int limit, int seconds) {
        // Shared hash tag keeps the atomic Lua operation compatible with Redis Cluster.
        keys.add("harness:{account-mail}:" + crypto.opaqueKey(suffix));
        args.add(Integer.toString(limit));
        args.add(Integer.toString(seconds));
    }

    private void execute(List<String> keys, List<String> args) {
        final Long retry;
        try { retry = redis.execute(LIMIT, keys, args.toArray()); }
        catch (RuntimeException ex) { throw new BusinessException(ErrorCode.EMAIL_UNAVAILABLE); }
        if (retry == null) throw new BusinessException(ErrorCode.EMAIL_UNAVAILABLE);
        if (retry > 0) throw new MailRateLimitException(retry);
    }
}
