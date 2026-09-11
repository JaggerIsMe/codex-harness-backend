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
import java.util.UUID;

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
        return createToken(userId, version, UUID.randomUUID().toString(), Instant.now());
    }

    public String createToken(Long userId, long version, String sid, Instant issuedAt) {
        return createToken(userId,version,sid,issuedAt,issuedAt.plus(properties.getJwtExpireMinutes(),ChronoUnit.MINUTES));
    }

    public String createToken(Long userId,long version,String sid,Instant issuedAt,Instant absoluteExpiresAt) {
        Instant normalExpiry=issuedAt.plus(properties.getJwtExpireMinutes(),ChronoUnit.MINUTES);
        Instant expiresAt=normalExpiry.isBefore(absoluteExpiresAt)?normalExpiry:absoluteExpiresAt;
        return Jwts.builder()
                .setIssuer(ISSUER)
                .setSubject(String.valueOf(userId))
                .claim("version", version)
                .claim("sid", sid)
                .setIssuedAt(Date.from(issuedAt))
                .setExpiration(Date.from(expiresAt))
                .signWith(signingKey())
                .compact();
    }

    public Long parseUserId(String token) {
        return Long.valueOf(parseClaims(token).getSubject());
    }

    public RedisLoginSessionStore.Session session(Claims claims) {
        try {
            String sid = claims.get("sid", String.class);
            Number version = claims.get("version", Number.class);
            if (sid == null || !UUID.fromString(sid).toString().equals(sid) || version == null
                    || version.longValue() < 0 || claims.getExpiration() == null) {
                throw new IllegalArgumentException("Invalid login session claims");
            }
            return new RedisLoginSessionStore.Session(version.longValue(), sid,
                    claims.getExpiration().toInstant().getEpochSecond());
        } catch (IllegalArgumentException | io.jsonwebtoken.RequiredTypeException exception) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
    }

    public Claims parseClaims(String token) {
        return parse(token,false);
    }

    /** Keeps only claims whose signature was verified, so the caller can check revocation before reporting expiry. */
    public Claims parseClaimsAllowExpired(String token) {
        return parse(token,true);
    }

    private Claims parse(String token,boolean allowExpired) {
        try {
            Claims claims = Jwts.parserBuilder()
                    .setSigningKey(signingKey())
                    .requireIssuer(ISSUER)
                    .build()
                    .parseClaimsJws(token)
                    .getBody();
            Long.parseLong(claims.getSubject());
            return claims;
        } catch (io.jsonwebtoken.ExpiredJwtException exception) {
            Claims claims=exception.getClaims();
            if (allowExpired && ISSUER.equals(claims.getIssuer())) {
                try { if (Long.parseLong(claims.getSubject())>0) return claims; }
                catch (IllegalArgumentException ignored) { }
            }
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        } catch (JwtException | IllegalArgumentException exception) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
    }

    public long getExpiresInSeconds() {
        return properties.getJwtExpireMinutes() * 60L;
    }

    public long getSessionIdleSeconds() { return properties.getSessionIdleMinutes()*60L; }
    public long getSessionAbsoluteSeconds() { return properties.getSessionAbsoluteDays()*86400L; }
    public long getRefreshBeforeSeconds() { return properties.getRefreshBeforeSeconds(); }
    public long getRefreshReplayGraceSeconds() { return properties.getRefreshReplayGraceSeconds(); }

    private SecretKey signingKey() {
        String secret = properties.getJwtSecret();
        if (!StringUtils.hasText(secret) || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("HARNESS_JWT_SECRET must contain at least 32 UTF-8 bytes");
        }
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }
}
