package com.myharness.codex.security;

import com.myharness.codex.config.SecurityProperties;
import com.myharness.codex.entity.enums.ErrorCode;
import com.myharness.codex.exception.BusinessException;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;

@Service
public class JwtTokenService {

    private static final String ISSUER = "my-harness-for-codex";

    private final SecurityProperties properties;

    public JwtTokenService(SecurityProperties properties) {
        this.properties = properties;
    }

    public String createToken(Long userId) {
        return createToken(userId, 0);
    }

    public String createToken(Long userId, long version) {
        Instant issuedAt = Instant.now();
        Instant expiresAt = issuedAt.plus(properties.getJwtExpireMinutes(), ChronoUnit.MINUTES);
        return Jwts.builder()
                .setIssuer(ISSUER)
                .setSubject(String.valueOf(userId))
                .claim("version", version)
                .setIssuedAt(Date.from(issuedAt))
                .setExpiration(Date.from(expiresAt))
                .signWith(signingKey())
                .compact();
    }

    public Long parseUserId(String token) {
        return Long.valueOf(parseClaims(token).getSubject());
    }

    public Claims parseClaims(String token) {
        try {
            Claims claims = Jwts.parserBuilder()
                    .setSigningKey(signingKey())
                    .requireIssuer(ISSUER)
                    .build()
                    .parseClaimsJws(token)
                    .getBody();
            Long.parseLong(claims.getSubject());
            return claims;
        } catch (JwtException | IllegalArgumentException exception) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
    }

    public long getExpiresInSeconds() {
        return properties.getJwtExpireMinutes() * 60L;
    }

    private SecretKey signingKey() {
        String secret = properties.getJwtSecret();
        if (!StringUtils.hasText(secret) || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("HARNESS_JWT_SECRET must contain at least 32 UTF-8 bytes");
        }
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }
}
