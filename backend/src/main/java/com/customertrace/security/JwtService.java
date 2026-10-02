package com.customertrace.security;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import javax.crypto.SecretKey;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Issues and verifies HMAC-SHA256 JSON Web Tokens.
 *
 * <p>Two token kinds, deliberately different in lifetime and storage:
 * <ul>
 *   <li><b>Access token</b> — short-lived (default 15m), returned in the JSON body and held
 *       in memory by the frontend. Never written to localStorage.</li>
 *   <li><b>Refresh token</b> — long-lived, delivered as an HttpOnly cookie so page scripts
 *       cannot read it, and stored server-side only as a BCrypt hash.</li>
 * </ul>
 *
 * <p>The token carries the user id and role names so the filter need not hit the database on
 * every request. Because the signature is verified with a constant-time HMAC before any claim
 * is read, claims cannot be forged or tampered with.
 */
@Service
public class JwtService {

    private static final Logger log = LoggerFactory.getLogger(JwtService.class);

    /** Claim holding the numeric user id. */
    public static final String CLAIM_USER_ID = "uid";
    /** Claim holding the authenticated user_id string. */
    public static final String CLAIM_USER_ID_NAME = "uname";
    /** Claim holding the role codes. */
    public static final String CLAIM_ROLES = "roles";
    /** Claim carrying the token family for refresh-token reuse detection. */
    public static final String CLAIM_FAMILY = "fam";

    private final SecurityProperties properties;
    private final SecretKey signingKey;

    public JwtService(SecurityProperties properties) {
        this.properties = properties;
        properties.validate();
        this.signingKey = Keys.hmacShaKeyFor(
                properties.getJwtSecret().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    public String newFamilyId() {
        return UUID.randomUUID().toString();
    }

    /**
     * Creates a signed access token for the given principal.
     *
     * @param userId  numeric id, re-checked against the database on privileged operations
     * @param userIdName the public {@code user_id} string
     * @param roles   authoritative role codes loaded from the database
     */
    public String createAccessToken(Long userId, String userIdName, List<String> roles) {
        Instant now = Instant.now();
        Instant expiry = now.plus(properties.getAccessTokenTtl());
        return Jwts.builder()
                .issuer(properties.getIssuer())
                .subject(userIdName)
                .claim(CLAIM_USER_ID, userId)
                .claim(CLAIM_USER_ID_NAME, userIdName)
                .claim(CLAIM_ROLES, roles)
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                .signWith(signingKey)
                .compact();
    }

    /**
     * Creates the refresh token. Its family id ties a rotation chain together so that
     * replaying an already-rotated token can be detected.
     */
    public String createRefreshToken(Long userId, String userIdName, String familyId) {
        Instant now = Instant.now();
        Instant expiry = now.plus(properties.getRefreshTokenTtl());
        return Jwts.builder()
                .issuer(properties.getIssuer())
                .subject(userIdName)
                .claim(CLAIM_USER_ID, userId)
                .claim(CLAIM_USER_ID_NAME, userIdName)
                .claim(CLAIM_FAMILY, familyId)
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                .signWith(signingKey)
                .compact();
    }

    /**
     * Verifies signature, issuer and expiry.
     *
     * @return the claims, or empty when the token is invalid for any reason. Never throws, so
     *         a malformed token cannot become a 500.
     */
    public java.util.Optional<Claims> parse(String token) {
        if (token == null || token.isBlank()) {
            return java.util.Optional.empty();
        }
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(signingKey)
                    .requireIssuer(properties.getIssuer())
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            return java.util.Optional.of(claims);
        } catch (JwtException | IllegalArgumentException ex) {
            // The token itself is never logged — only the failure category.
            log.debug("Rejected access token: {}", ex.getClass().getSimpleName());
            return java.util.Optional.empty();
        }
    }

    public String extractFamily(String refreshToken) {
        return parse(refreshToken).map(c -> c.get(CLAIM_FAMILY, String.class)).orElse(null);
    }

    public long getAccessTokenTtlSeconds() {
        return properties.getAccessTokenTtl().toSeconds();
    }

    public SecurityProperties getProperties() {
        return properties;
    }
}