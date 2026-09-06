package com.myharness.codex.security;

import com.myharness.codex.config.SecurityProperties;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ModelSecretCipherTest {
    @Test void encryptsWithRandomNonceAndDecrypts(){SecurityProperties p=new SecurityProperties();p.setModelSecretKey("unit-test-secret-material");ModelSecretCipher cipher=new ModelSecretCipher(p);String first=cipher.encrypt("sk-value"),second=cipher.encrypt("sk-value");assertNotEquals(first,second);assertFalse(first.contains("sk-value"));assertEquals("sk-value",cipher.decrypt(first));}
}
