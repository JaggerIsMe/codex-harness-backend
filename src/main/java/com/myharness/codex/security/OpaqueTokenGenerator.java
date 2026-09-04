package com.myharness.codex.security;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.Base64;

@Component
public class OpaqueTokenGenerator {
    private final SecureRandom random = new SecureRandom();

    public String deviceToken() {
        byte[] value = new byte[32];
        random.nextBytes(value);
        return "hdt_" + Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    public String deviceCode() {
        byte[] value = new byte[10];
        random.nextBytes(value);
        return "device-" + Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    public String enrollmentCode() {
        final char[] alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();
        StringBuilder code = new StringBuilder("ENR-");
        for (int i = 0; i < 8; i++) {
            if (i == 4) code.append('-');
            code.append(alphabet[random.nextInt(alphabet.length)]);
        }
        return code.toString();
    }
}
