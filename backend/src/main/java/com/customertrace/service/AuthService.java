package com.customertrace.service;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.customertrace.domain.AuditLog;
import com.customertrace.domain.Creator;
import com.customertrace.domain.RefreshToken;
import com.customertrace.domain.Restaurant;
import com.customertrace.domain.User;
import com.customertrace.exception.ApiErrors;
import com.customertrace.repository.AuditLogRepository;
import com.customertrace.repository.CreatorRepository;
import com.customertrace.repository.RefreshTokenRepository;
import com.customertrace.repository.RestaurantRepository;
import com.customertrace.repository.UserRepository;
import com.customertrace.security.JwtService;
import com.customertrace.security.RoleCodes;
import com.customertrace.security.SecurityProperties;
import com.customertrace.security.TokenDigest;
import com.customertrace.web.dto.AuthDtos;

/**
 * Authentication: password verification, token issuance, refresh rotation and logout.
 *
 * <p>Server-side checks, none of which trust the client:
 * <ul>
 *   <li>the account exists, is ACTIVE, and is not inside a lockout window;</li>
 *   <li>the password matches its BCrypt hash;</li>
 *   <li>the role comes from {@code user_roles}. The id suffix and the client-supplied portal only
 *       shape the error message and never grant access.</li>
 * </ul>
 *
 * <p>Passwords, hashes and tokens are never logged.
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final RestaurantRepository restaurantRepository;
    private final CreatorRepository creatorRepository;
    private final AuditLogRepository auditLogRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final SecurityProperties securityProperties;

    public AuthService(UserRepository userRepository,
                       RefreshTokenRepository refreshTokenRepository,
                       RestaurantRepository restaurantRepository,
                       CreatorRepository creatorRepository,
                       AuditLogRepository auditLogRepository,
                       PasswordEncoder passwordEncoder,
                       JwtService jwtService,
                       SecurityProperties securityProperties) {
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.restaurantRepository = restaurantRepository;
        this.creatorRepository = creatorRepository;
        this.auditLogRepository = auditLogRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.securityProperties = securityProperties;
    }

/**
     * Verifies credentials and issues an access token plus a refresh cookie.
     *
     * @throws com.customertrace.exception.ApiException on bad credentials, wrong portal, or a
     *                                                disabled/locked account
     */
    @Transactional
    public IssuedTokens login(AuthDtos.LoginRequest request, String ip) {
        String userId = request.userId() == null ? "" : request.userId().trim();

        Optional<User> found = userRepository.findByUserIdIgnoreCase(userId);
        if (found.isEmpty()) {
            auditLogRepository.save(AuditLog.of(null, userId, "LOGIN_UNKNOWN_USER", "USER", null,
                    "No such account", ip));
            // Same wording as a wrong password, so the response cannot enumerate accounts.
            throw ApiErrors.unauthorized("Incorrect User ID or password.");
        }

        User user = found.get();

        if (user.isLocked()) {
            throw ApiErrors.unauthorized(
                    "This account is temporarily locked after too many failed attempts. "
                            + "Please try again later.");
        }

        if (!user.isActive()) {
            auditLogRepository.save(AuditLog.of(user.getId(), user.getUserId(),
                    "LOGIN_DISABLED_ACCOUNT", "USER", user.getId(),
                    "Attempt to sign in to a disabled account", ip));
            throw ApiErrors.forbidden("This account has been disabled. Please contact support.");
        }

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            registerFailure(user, ip);
            throw ApiErrors.unauthorized("Incorrect User ID or password.");
        }

        verifyPortalExpectation(user, request.portal());

        user.setFailedCount(0);
        user.setLockedUntil(null);
        user.setLastLoginAt(Instant.now());
        userRepository.save(user);

        auditLogRepository.save(AuditLog.of(user.getId(), user.getUserId(), "LOGIN_SUCCESS",
                "USER", user.getId(), "Signed in as " + String.join(",", user.getRoleCodes()), ip));

        return issueTokens(user);
    }

    /**
     * Confirms the account holds the role the chosen portal requires.
     *
     * <p>A creator therefore cannot sign in through the restaurant form, but the decision rests
     * on the stored role rather than on the id suffix.
     */
    private void verifyPortalExpectation(User user, String portal) {
        if (portal == null || portal.isBlank()) {
            return;
        }
        String expectedRole = RoleCodes.roleForPortal(portal);
        if (expectedRole == null) {
            return;
        }
        if (!user.getRoleCodes().contains(expectedRole)) {
            throw ApiErrors.forbidden(
                    "This account does not have access to the " + portal + " portal.");
        }
        String expectedSuffix = RoleCodes.suffixForPortal(portal);
        if (expectedSuffix != null
                && !user.getUserId().toLowerCase(Locale.ROOT).endsWith(expectedSuffix)) {
            // Informational only; the stored role above is what authorizes.
            log.debug("Account {} signed in on the {} portal without the conventional suffix.",
                    user.getUserId(), portal);
        }
    }

    private void registerFailure(User user, String ip) {
        int failures = user.getFailedCount() + 1;
        user.setFailedCount(failures);
        if (failures >= securityProperties.getMaxFailedAttempts()) {
            user.setLockedUntil(Instant.now().plus(securityProperties.getLockoutDuration()));
            user.setFailedCount(0);
            log.warn("Account {} locked after {} failed sign-in attempts.",
                    user.getUserId(), failures);
        }
        userRepository.save(user);
        auditLogRepository.save(AuditLog.of(user.getId(), user.getUserId(), "LOGIN_FAILED",
                "USER", user.getId(), "Failed sign-in attempt " + failures, ip));
    }
/**
     * Issues the access token, persists the refresh hash, and assembles the response.
     *
     * <p>The raw refresh token is returned so the caller can seal it into the HttpOnly cookie;
     * it is never persisted in clear text and never returned in the JSON body.
     */
    private IssuedTokens issueTokens(User user) {
        List<String> roleCodes = List.copyOf(user.getRoleCodes());
        String accessToken = jwtService.createAccessToken(user.getId(), user.getUserId(), roleCodes);

        String familyId = jwtService.newFamilyId();
        String refreshToken = jwtService.createRefreshToken(user.getId(), user.getUserId(), familyId);
        persistRefreshToken(user, refreshToken, familyId);

        Long restaurantId = restaurantRepository.findByUserId(user.getId())
                .map(Restaurant::getId).orElse(null);
        Long creatorId = creatorRepository.findByUserId(user.getId())
                .map(Creator::getId).orElse(null);

        return new IssuedTokens(
                new AuthDtos.LoginResponse(accessToken, "Bearer",
                        jwtService.getAccessTokenTtlSeconds(), user.getUserId(),
                        user.getDisplayName(), roleCodes, restaurantId, creatorId),
                refreshToken);
    }

    private void persistRefreshToken(User user, String rawToken, String familyId) {
        RefreshToken entity = new RefreshToken();
        entity.setUserId(user.getId());
        // Only the digest is stored; the raw token exists solely in the HttpOnly cookie.
        entity.setTokenHash(TokenDigest.of(rawToken));
        entity.setFamilyId(familyId);
        entity.setExpiresAt(Instant.now().plus(securityProperties.getRefreshTokenTtl()));
        refreshTokenRepository.save(entity);
    }

    /**
     * Rotates a refresh token and issues a fresh pair.
     *
     * <p>Rotation is single-use. Presenting a token that was already rotated away is treated as
     * theft and revokes the entire family.
     */
    @Transactional
    public IssuedTokens refresh(String presentedToken, String ip) {
        if (presentedToken == null || presentedToken.isBlank()) {
            throw ApiErrors.unauthorized("No refresh token was supplied.");
        }
        Optional<RefreshToken> stored = matchStoredToken(presentedToken);
        if (stored.isEmpty()) {
            throw ApiErrors.unauthorized("The refresh token is not recognised.");
        }

        RefreshToken token = stored.get();

        if (token.isRevoked()) {
            // A rotated token was replayed: assume compromise and drop the whole chain.
            refreshTokenRepository.deleteByFamilyIdAndRevokedAtIsNull(token.getFamilyId());
            auditLogRepository.save(AuditLog.of(token.getUserId(), null, "REFRESH_TOKEN_REUSE",
                    "USER", token.getUserId(),
                    "Rotated refresh token replayed; family revoked", ip));
            throw ApiErrors.unauthorized("This session has been revoked. Please sign in again.");
        }

        if (token.isExpired(Instant.now())) {
            throw ApiErrors.unauthorized("Your session has expired. Please sign in again.");
        }

        Optional<User> found = userRepository.findById(token.getUserId());
        if (found.isEmpty() || !found.get().isActive()) {
            throw ApiErrors.unauthorized("This account is no longer active.");
        }
        User user = found.get();

        token.setRevokedAt(Instant.now());
        refreshTokenRepository.save(token);

        List<String> roleCodes = List.copyOf(user.getRoleCodes());
        String accessToken = jwtService.createAccessToken(user.getId(), user.getUserId(), roleCodes);
        String newRefresh = jwtService.createRefreshToken(user.getId(), user.getUserId(),
                token.getFamilyId());
        persistRefreshToken(user, newRefresh, token.getFamilyId());

        Long restaurantId = restaurantRepository.findByUserId(user.getId())
                .map(Restaurant::getId).orElse(null);
        Long creatorId = creatorRepository.findByUserId(user.getId())
                .map(Creator::getId).orElse(null);

        return new IssuedTokens(
                new AuthDtos.LoginResponse(accessToken, "Bearer",
                        jwtService.getAccessTokenTtlSeconds(), user.getUserId(),
                        user.getDisplayName(), roleCodes, restaurantId, creatorId),
                newRefresh);
    }

    /**
     * Finds the stored record for a raw refresh token.
     *
     * <p>The digest is deterministic, so this is an indexed equality lookup rather than a scan.
     * Rotated rows are deliberately retained so a replay is detected rather than reported as
     * "unknown".
     */
    private Optional<RefreshToken> matchStoredToken(String rawToken) {
        return refreshTokenRepository.findByTokenHash(TokenDigest.of(rawToken));
    }

    /** Revokes the whole refresh-token family, ending the session. */
    @Transactional
    public void logout(String presentedToken, String ip) {
        if (presentedToken == null || presentedToken.isBlank()) {
            return;
        }
        String familyId = jwtService.extractFamily(presentedToken);
        if (familyId != null) {
            refreshTokenRepository.deleteByFamilyIdAndRevokedAtIsNull(familyId);
            auditLogRepository.save(AuditLog.of(null, null, "LOGOUT", "SESSION", null,
                    "Refresh token family revoked", ip));
        }
    }

    /** Loads the profile shown by {@code GET /api/auth/me}. */
    @Transactional(readOnly = true)
    public AuthDtos.CurrentUserResponse currentUser(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> ApiErrors.notFound("Your account"));

        Restaurant restaurant = restaurantRepository.findByUserId(userId).orElse(null);
        Creator creator = creatorRepository.findByUserId(userId).orElse(null);

        return new AuthDtos.CurrentUserResponse(
                user.getUserId(), user.getDisplayName(), user.getEmail(),
                List.copyOf(user.getRoleCodes()),
                restaurant == null ? null : restaurant.getId(),
                restaurant == null ? null : restaurant.getName(),
                creator == null ? null : creator.getId(),
                creator == null ? null : creator.getHandle());
    }

    public SecurityProperties getSecurityProperties() {
        return securityProperties;
    }

    /** A freshly issued token pair: the API response plus the raw refresh token for the cookie. */
    public record IssuedTokens(AuthDtos.LoginResponse response, String refreshToken) {
    }
}
