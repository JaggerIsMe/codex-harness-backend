package com.myharness.codex.security;

import com.myharness.codex.config.SecurityProperties;
import com.myharness.codex.entity.enums.ErrorCode;
import com.myharness.codex.exception.BusinessException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class JwtRenewalPolicyTest {
    private static final String SECRET="renewal-policy-test-secret-with-at-least-32-bytes";

    @Test void defaultPolicyHasTwoHourAccessAndIdleWithSevenDayAbsoluteLimit() {
        var properties=properties();
        assertDoesNotThrow(properties::validateSessionPolicy);
        var jwt=new JwtTokenService(properties);
        assertEquals(7200,jwt.getExpiresInSeconds());
        assertEquals(7200,jwt.getSessionIdleSeconds());
        assertEquals(604800,jwt.getSessionAbsoluteSeconds());
        assertEquals(600,jwt.getRefreshBeforeSeconds());
        assertTrue(properties.isRefreshCookieSecure());
    }

    @Test void invalidLifetimeAndRefreshWindowsAreRejected() {
        var properties=properties();
        properties.setSessionIdleMinutes(0);
        assertThrows(IllegalStateException.class,properties::validateSessionPolicy);
        properties=properties();
        properties.setSessionAbsoluteDays(0);
        assertThrows(IllegalStateException.class,properties::validateSessionPolicy);
        properties=properties();
        properties.setRefreshBeforeSeconds(7200);
        assertThrows(IllegalStateException.class,properties::validateSessionPolicy);
        properties=properties();
        properties.setRefreshReplayGraceSeconds(61);
        assertThrows(IllegalStateException.class,properties::validateSessionPolicy);
    }

    @Test void expiredClaimsStillRequireTheCorrectIssuerAndSignature() {
        var jwt=new JwtTokenService(properties());
        String sid=UUID.randomUUID().toString();
        String expired=jwt.createToken(1L,2,sid,Instant.now().minusSeconds(7300));
        assertEquals("1",jwt.parseClaimsAllowExpired(expired).getSubject());
        assertEquals(ErrorCode.UNAUTHORIZED,assertThrows(BusinessException.class,()->jwt.parseClaims(expired)).getErrorCode());
        String otherIssuer=Jwts.builder().setIssuer("other-issuer").setSubject("1").claim("sid",sid).claim("version",2)
                .setExpiration(Date.from(Instant.now().minusSeconds(1))).signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8))).compact();
        assertThrows(BusinessException.class,()->jwt.parseClaimsAllowExpired(otherIssuer));
        String untrusted=Jwts.builder().setIssuer("my-harness-for-codex").setSubject("1").claim("sid",sid).claim("version",2)
                .setExpiration(Date.from(Instant.now().minusSeconds(1)))
                .signWith(Keys.hmacShaKeyFor("different-signing-secret-with-at-least-32-bytes".getBytes(StandardCharsets.UTF_8))).compact();
        assertThrows(BusinessException.class,()->jwt.parseClaimsAllowExpired(untrusted));
    }

    private SecurityProperties properties() {
        var properties=new SecurityProperties();
        properties.setJwtSecret(SECRET);
        return properties;
    }
}
