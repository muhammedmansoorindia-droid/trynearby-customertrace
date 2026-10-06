package com.customertrace.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * One-way digest of a refresh token, used as the {@code token_hash} column value.
 *
 * <p>Refresh tokens are server-minted, high-entropy, signed values — never a user-chosen secret —
 * so a fast deterministic digest is the right tool here, and it keeps the lookup an indexed
 * equality match.
 *
 * <p>This deliberately replaces BCrypt. BCrypt silently ignores input past 72 bytes, and a
 * refresh JWT is roughly 330 characters, so every token belonging to one user reduced to the
 * same 72-byte prefix. Comparing them with {@code matches()} reported whichever stored row came
 * first — normally an already-rotated one — and the legitimate session was rejected as a replay.
 */
public final class TokenDigest {

    private static final String ALGORITHM = "SHA-256";

    private TokenDigest() {
    }

    /**
     * Returns the lowercase hex SHA-256 digest of the supplied token.
     *
     * <p>64 characters, which fits the existing {@code token_hash} column without a migration.
     */
    public static String of(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance(ALGORITHM);
            return HexFormat.of().formatHex(digest.digest(rawToken.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is required of every Java platform; its absence is unrecoverable.
            throw new IllegalStateException(ALGORITHM + " is unavailable", e);
        }
    }
}