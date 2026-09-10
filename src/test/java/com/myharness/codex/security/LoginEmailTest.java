package com.myharness.codex.security;

import com.myharness.codex.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class LoginEmailTest {
    @Test void canonicalizesCaseAndWhitespaceWithoutMergingAliases() {
        assertEquals("first.last+tag@example.com", LoginEmail.normalize("  First.Last+TAG@EXAMPLE.COM  "));
        assertNotEquals(LoginEmail.normalize("first.last@example.com"), LoginEmail.normalize("firstlast@example.com"));
        assertNotEquals(LoginEmail.normalize("member+tag@example.com"), LoginEmail.normalize("member@example.com"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"admin", "@example.com", "a@@example.com", "a b@example.com", ".a@example.com",
            "a..b@example.com", "a.@example.com", "a@-example.com", "a@example.com.", "a@example", "a@exam\nple.com"})
    void rejectsMalformedAddresses(String input) {
        assertThrows(BusinessException.class, () -> LoginEmail.normalize(input));
    }

    @Test void enforcesLocalAndTotalAddressLimits() {
        assertThrows(BusinessException.class, () -> LoginEmail.normalize("a".repeat(65) + "@example.com"));
        assertThrows(BusinessException.class, () -> LoginEmail.normalize("a".repeat(64) + "@" + ("b".repeat(63) + ".").repeat(3) + "com"));
    }
}
