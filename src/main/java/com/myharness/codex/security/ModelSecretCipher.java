package com.myharness.codex.security;

import com.myharness.codex.config.SecurityProperties;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/** Encrypts administrator-managed model credentials at the persistence seam. */
@Component
public class ModelSecretCipher {
    private static final byte FORMAT_VERSION=1;
    private final SecretKeySpec key;
    private final SecureRandom random=new SecureRandom();

    public ModelSecretCipher(SecurityProperties properties) {
        String material=properties.getModelSecretKey();
        if(material==null || material.isBlank()) material=properties.getJwtSecret();
        if(material==null || material.isBlank()) throw new IllegalStateException("Model secret encryption key is not configured");
        try {
            MessageDigest digest=MessageDigest.getInstance("SHA-256");
            digest.update("my-harness/model-secret/v1\0".getBytes(StandardCharsets.UTF_8));
            key=new SecretKeySpec(digest.digest(material.getBytes(StandardCharsets.UTF_8)),"AES");
        } catch(Exception failure) {throw new IllegalStateException("Unable to initialize model secret encryption",failure);}
    }

    public String encrypt(String plaintext) {
        if(plaintext==null || plaintext.isBlank()) throw new IllegalArgumentException("Model API Key must not be blank");
        try {
            byte[] nonce=new byte[12];random.nextBytes(nonce);
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE,key,new GCMParameterSpec(128,nonce));
            byte[] encrypted=cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            ByteBuffer output=ByteBuffer.allocate(1+nonce.length+encrypted.length);
            output.put(FORMAT_VERSION).put(nonce).put(encrypted);
            return Base64.getEncoder().encodeToString(output.array());
        } catch(Exception failure) {throw new IllegalStateException("Unable to encrypt model API Key",failure);}
    }

    public String decrypt(String encoded) {
        try {
            byte[] stored=Base64.getDecoder().decode(encoded);ByteBuffer input=ByteBuffer.wrap(stored);
            if(input.get()!=FORMAT_VERSION || input.remaining()<29) throw new IllegalArgumentException("Unsupported encrypted secret");
            byte[] nonce=new byte[12];input.get(nonce);byte[] encrypted=new byte[input.remaining()];input.get(encrypted);
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE,key,new GCMParameterSpec(128,nonce));
            return new String(cipher.doFinal(encrypted),StandardCharsets.UTF_8);
        } catch(Exception failure) {throw new IllegalStateException("Unable to decrypt model API Key",failure);}
    }
}
