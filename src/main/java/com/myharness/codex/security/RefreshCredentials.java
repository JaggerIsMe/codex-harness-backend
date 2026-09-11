package com.myharness.codex.security;

import com.myharness.codex.entity.enums.ErrorCode;
import com.myharness.codex.exception.BusinessException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/** Random bearer secrets are never persisted; Redis stores only their SHA-256 digests. */
public final class RefreshCredentials {
    private static final SecureRandom RANDOM=new SecureRandom();
    private RefreshCredentials() {}
    public static String create() {
        byte[] secret=new byte[32]; RANDOM.nextBytes(secret);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
    }
    public static String digest(String secret) {
        if (secret==null || !secret.matches("[A-Za-z0-9_-]{43}")) throw new BusinessException(ErrorCode.UNAUTHORIZED);
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.US_ASCII))); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }
}
