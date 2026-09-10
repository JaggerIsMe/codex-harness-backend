package com.myharness.codex.service.mail;

import com.myharness.codex.config.AccountMailProperties;
import com.myharness.codex.entity.enums.ErrorCode;
import com.myharness.codex.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AccountMailRateLimiterTest {
    @Test void redisOutageFailsClosedWithoutLeakingDriverErrors() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        AccountMailProperties properties = AccountMailCryptoTest.properties();
        AccountMailRateLimiter limiter = new AccountMailRateLimiter(redis, new AccountMailCrypto(properties), properties);
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenThrow(new DataAccessResourceFailureException("sensitive Redis connection detail"));
        BusinessException exception = assertThrows(BusinessException.class,
                () -> limiter.checkSend("person@example.com", "ACTIVATION", "127.0.0.1"));
        assertEquals(ErrorCode.EMAIL_UNAVAILABLE, exception.getErrorCode());
        assertFalse(exception.getMessage().contains("sensitive"));
    }

    @Test void returnsServerCooldownAndTreatsMissingScriptResultsAsUnavailable() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        AccountMailProperties properties = AccountMailCryptoTest.properties();
        AccountMailRateLimiter limiter = new AccountMailRateLimiter(redis, new AccountMailCrypto(properties), properties);
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(42L, null);
        MailRateLimitException exception = assertThrows(MailRateLimitException.class,
                () -> limiter.checkVerify("person@example.com", "PASSWORD_RESET", "127.0.0.1"));
        assertEquals(42, exception.getRetryAfterSeconds());
        assertEquals(ErrorCode.EMAIL_UNAVAILABLE, assertThrows(BusinessException.class,
                () -> limiter.checkVerify("person@example.com", "PASSWORD_RESET", "127.0.0.1")).getErrorCode());
    }
}
