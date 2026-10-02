package com.customertrace.web.dto;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Authentication payloads.
 *
 * <p>{@code portal} is a UX hint used only to produce a clearer error when the account id
 * suffix does not match the screen the user opened. The authoritative role always comes from the
 * {@code user_roles} table, never from this request.
 */
public final class AuthDtos {

    private AuthDtos() {
    }

    public record LoginRequest(
            @NotBlank(message = "User ID is required")
            @Size(max = 64, message = "User ID must be at most 64 characters")
            String userId,

            @NotBlank(message = "Password is required")
            @Size(min = 8, max = 72, message = "Password must be between 8 and 72 characters")
            String password,

            String portal) {
    }

    /** Successful sign-in response. The refresh token is delivered as an HttpOnly cookie. */
    public record LoginResponse(
            String accessToken,
            String tokenType,
            long expiresInSeconds,
            String userId,
            String displayName,
            List<String> roles,
            Long restaurantId,
            Long creatorId) {
    }

    /** The authenticated principal, used by {@code GET /api/auth/me}. */
    public record CurrentUserResponse(
            String userId,
            String displayName,
            String email,
            List<String> roles,
            Long restaurantId,
            String restaurantName,
            Long creatorId,
            String creatorHandle) {
    }
}