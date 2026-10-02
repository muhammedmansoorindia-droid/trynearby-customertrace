package com.customertrace.repository;

import java.time.Instant;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.customertrace.domain.RefreshToken;

/** Refresh-token persistence. Only a BCrypt hash is stored. */
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /** Revokes an entire rotation chain; used on logout and on detected token reuse. */
    long deleteByFamilyIdAndRevokedAtIsNull(String familyId);
}