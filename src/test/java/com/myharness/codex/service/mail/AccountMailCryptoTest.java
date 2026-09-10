package com.myharness.codex.service.mail;

import com.myharness.codex.config.AccountMailProperties;
import com.myharness.codex.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AccountMailCryptoTest {
    static AccountMailProperties properties() {
        AccountMailProperties properties = new AccountMailProperties();
        byte[] hmac = new byte[32];
        byte[] encryption = new byte[32];
        Arrays.fill(hmac, (byte) 11);
        Arrays.fill(encryption, (byte) 22);
        properties.setHmacKey(Base64.getEncoder().encodeToString(hmac));
        properties.setEncryptionKey(Base64.getEncoder().encodeToString(encryption));
        return properties;
    }

    @Test void credentialsHaveExpectedEntropyAndKeepLeadingZeros() {
        SecureRandom random = mock(SecureRandom.class);
        when(random.nextInt(1_000_000)).thenReturn(7);
        AccountMailCrypto crypto = new AccountMailCrypto(properties(), random);
        assertEquals("000007", crypto.newResetCode());
        assertEquals(32, Base64.getUrlDecoder().decode(crypto.newActivationToken()).length);
    }

    @Test void resetDigestIsBoundToUserMailboxGenerationAndPurpose() {
        AccountMailCrypto crypto = new AccountMailCrypto(properties());
        String digest = crypto.resetDigest(1L, "one@example.com", "generation-1", "000007");
        assertEquals(64, digest.length());
        assertNotEquals(digest, crypto.resetDigest(2L, "one@example.com", "generation-1", "000007"));
        assertNotEquals(digest, crypto.resetDigest(1L, "two@example.com", "generation-1", "000007"));
        assertNotEquals(digest, crypto.resetDigest(1L, "one@example.com", "generation-2", "000007"));
        assertNotEquals(digest, crypto.activationDigest("000007"));
        assertTrue(crypto.matches(digest, crypto.resetDigest(1L, "one@example.com", "generation-1", "000007")));
        assertFalse(crypto.matches(digest, null));
    }

    @Test void encryptedCredentialsCannotBeMovedToAnotherTaskOrAltered() {
        AccountMailCrypto crypto = new AccountMailCrypto(properties());
        String first = crypto.encrypt("000007", "task-1");
        String second = crypto.encrypt("000007", "task-1");
        assertNotEquals(first, second);
        assertFalse(first.contains("000007"));
        assertEquals("000007", crypto.decrypt(first, "task-1"));
        assertThrows(BusinessException.class, () -> crypto.decrypt(first, "task-2"));
        byte[] damaged = Base64.getDecoder().decode(first.substring(3));
        damaged[damaged.length - 1] ^= 1;
        assertThrows(BusinessException.class, () -> crypto.decrypt("v1:" + Base64.getEncoder().encodeToString(damaged), "task-1"));
    }

    @Test void requiresIndependentKeysAndRejectsMissingOrWeakKeys() {
        AccountMailProperties properties = properties();
        AccountMailCrypto crypto = new AccountMailCrypto(properties);
        assertDoesNotThrow(crypto::validateKeys);
        properties.setEncryptionKey(properties.getHmacKey());
        assertThrows(IllegalStateException.class, crypto::validateKeys);
        properties.setHmacKey("secret");
        assertThrows(IllegalStateException.class, crypto::validateKeys);
    }
}
