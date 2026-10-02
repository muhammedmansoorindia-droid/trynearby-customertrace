package com.customertrace.security;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Security configuration bound from {@code customer-trace.security.*}.
 *
 * The JWT secret has no default on purpose: {@link #validate()} fails fast at startup if it
 * is missing or still the placeholder, so a production deployment cannot silently run with a
 * well-known signing key.
 */
@ConfigurationProperties(prefix = "customer-trace.security")
public class SecurityProperties {

    /** Rejected outright if supplied, to prevent shipping the documented example value. */
    public static final String PLACEHOLDER_SECRET = "change-me-in-production-secret-key";

    private String jwtSecret = "";
    private Duration accessTokenTtl = Duration.ofMinutes(15);
    private Duration refreshTokenTtl = Duration.ofDays(7);
    private String issuer = "customer-trace-api";
    private String refreshCookieName = "ct_refresh";
    private boolean refreshCookieSecure = false;
    private String refreshCookieSameSite = "Lax";
    private int maxFailedAttempts = 5;
    private Duration lockoutDuration = Duration.ofMinutes(15);

    public String getJwtSecret() {
        return jwtSecret;
    }

    public void setJwtSecret(String jwtSecret) {
        this.jwtSecret = jwtSecret;
    }

    public Duration getAccessTokenTtl() {
        return accessTokenTtl;
    }

    public void setAccessTokenTtl(Duration accessTokenTtl) {
        this.accessTokenTtl = accessTokenTtl;
    }

    public Duration getRefreshTokenTtl() {
        return refreshTokenTtl;
    }

    public void setRefreshTokenTtl(Duration refreshTokenTtl) {
        this.refreshTokenTtl = refreshTokenTtl;
    }

    public String getIssuer() {
        return issuer;
    }

    public void setIssuer(String issuer) {
        this.issuer = issuer;
    }

    public String getRefreshCookieName() {
        return refreshCookieName;
    }

    public void setRefreshCookieName(String refreshCookieName) {
        this.refreshCookieName = refreshCookieName;
    }

    public boolean isRefreshCookieSecure() {
        return refreshCookieSecure;
    }

    public void setRefreshCookieSecure(boolean refreshCookieSecure) {
        this.refreshCookieSecure = refreshCookieSecure;
    }

    public String getRefreshCookieSameSite() {
        return refreshCookieSameSite;
    }

    public void setRefreshCookieSameSite(String refreshCookieSameSite) {
        this.refreshCookieSameSite = refreshCookieSameSite;
    }

    public int getMaxFailedAttempts() {
        return maxFailedAttempts;
    }

    public void setMaxFailedAttempts(int maxFailedAttempts) {
        this.maxFailedAttempts = maxFailedAttempts;
    }

    public Duration getLockoutDuration() {
        return lockoutDuration;
    }

    public void setLockoutDuration(Duration lockoutDuration) {
        this.lockoutDuration = lockoutDuration;
    }

    /**
     * Fails fast on a missing or placeholder signing key.
     *
     * @throws IllegalStateException when the secret is unusable
     */
    public void validate() {
        if (jwtSecret == null || jwtSecret.isBlank()) {
            throw new IllegalStateException(
                    "customer-trace.security.jwt-secret is not set. Provide the JWT_SECRET "
                            + "environment variable (see .env.example).");
        }
        if (PLACEHOLDER_SECRET.equals(jwtSecret)) {
            throw new IllegalStateException(
                    "customer-trace.security.jwt-secret is still the documented placeholder. "
                            + "Generate a unique secret before starting the application.");
        }
        // HS256 requires >= 256 bits of key material.
        if (jwtSecret.getBytes(java.nio.charset.StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException(
                    "customer-trace.security.jwt-secret must be at least 32 bytes for HS256.");
        }
    }
}