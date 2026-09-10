package com.myharness.codex.service.mail;

import com.myharness.codex.config.AccountMailProperties;
import com.myharness.codex.entity.enums.ErrorCode;
import com.myharness.codex.exception.BusinessException;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Locale;
import java.util.HexFormat;

/** Credentials are purpose-bound; delivery encryption has a separate key and authenticated metadata. */
@Component
public class AccountMailCrypto {
    private final AccountMailProperties properties;
    private final SecureRandom random;

    @Autowired
    public AccountMailCrypto(AccountMailProperties properties) { this(properties, new SecureRandom()); }

    AccountMailCrypto(AccountMailProperties properties, SecureRandom random) {
        this.properties = properties;
        this.random = random;
    }

    public String newActivationToken() {
        byte[] token = new byte[32];
        random.nextBytes(token);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(token);
    }

    public String newResetCode() { return String.format(Locale.ROOT, "%06d", random.nextInt(1_000_000)); }

    public String activationDigest(String token) { return sha256("ACTIVATION\n" + token); }

    public String resetDigest(Long userId, String email, String generation, String code) {
        return hmac("PASSWORD_RESET\n" + userId + "\n" + email + "\n" + generation + "\n" + code);
    }

    public boolean matches(String expected, String supplied) {
        return expected != null && supplied != null && MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.US_ASCII), supplied.getBytes(StandardCharsets.US_ASCII));
    }

    public String opaqueKey(String value) { return hmac("RATE_LIMIT\n" + value); }

    private String hmac(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(decodeKey(properties.getHmacKey(), "hmac-key"), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException ex) { throw unavailable(); }
    }

    private String sha256(String value) {
        try { return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (GeneralSecurityException ex) { throw unavailable(); }
    }

    public void validateKeys() {
        byte[] hmac = decodeKey(properties.getHmacKey(), "hmac-key");
        byte[] encryption = decodeKey(properties.getEncryptionKey(), "encryption-key");
        if (MessageDigest.isEqual(hmac, encryption)) {
            throw new IllegalStateException("harness.mail.hmac-key 与 encryption-key 必须使用独立密钥");
        }
    }

    private byte[] decodeKey(String value, String name) {
        try {
            byte[] key = Base64.getDecoder().decode(value == null ? "" : value);
            if (key.length != 32) throw new IllegalArgumentException();
            return key;
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("harness.mail." + name + " 必须为 32 字节随机密钥的 Base64 编码");
        }
    }

    public String encrypt(String plaintext, String binding) {
        try {
            byte[] nonce = new byte[12];
            random.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(decodeKey(properties.getEncryptionKey(), "encryption-key"), "AES"), new GCMParameterSpec(128, nonce));
            cipher.updateAAD(binding.getBytes(StandardCharsets.UTF_8));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return "v1:" + Base64.getEncoder().encodeToString(ByteBuffer.allocate(nonce.length + ciphertext.length).put(nonce).put(ciphertext).array());
        } catch (GeneralSecurityException ex) { throw unavailable(); }
    }

    public String decrypt(String encoded, String binding) {
        try {
            if (encoded == null || !encoded.startsWith("v1:")) throw new GeneralSecurityException();
            byte[] content = Base64.getDecoder().decode(encoded.substring(3));
            if (content.length < 28) throw new GeneralSecurityException();
            ByteBuffer buffer = ByteBuffer.wrap(content);
            byte[] nonce = new byte[12];
            buffer.get(nonce);
            byte[] ciphertext = new byte[buffer.remaining()];
            buffer.get(ciphertext);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(decodeKey(properties.getEncryptionKey(), "encryption-key"), "AES"), new GCMParameterSpec(128, nonce));
            cipher.updateAAD(binding.getBytes(StandardCharsets.UTF_8));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException ex) { throw unavailable(); }
    }

    private BusinessException unavailable() { return new BusinessException(ErrorCode.EMAIL_UNAVAILABLE); }
}
