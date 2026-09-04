package com.myharness.codex.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public final class SecureDigests {
    private SecureDigests() { }

    public static String sha256(String value) {
        byte[] digest;
        try {
            digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
        StringBuilder result = new StringBuilder(64);
        for (byte current : digest) {
            result.append(String.format("%02x", current & 0xff));
        }
        return result.toString();
    }

    public static boolean matches(String plaintext, String expectedHex) {
        if (plaintext == null || expectedHex == null) {
            return false;
        }
        return MessageDigest.isEqual(sha256(plaintext).getBytes(StandardCharsets.US_ASCII),
                expectedHex.getBytes(StandardCharsets.US_ASCII));
    }
}
