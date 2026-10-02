package com.customertrace.web.controller;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.customertrace.config.SecurityConfig;
import com.customertrace.security.CurrentUser;
import com.customertrace.security.JwtService;
import com.customertrace.security.SecurityProperties;
import com.customertrace.service.AuthService;
import com.customertrace.web.dto.AuthDtos;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

/**
 * Authentication endpoints.
 *
 * <p>The access token is returned in the JSON body for the frontend to keep in memory. The
 * refresh token is set as an HttpOnly cookie so no page script can read it, which is why the
 * frontend does not persist a long-lived credential in localStorage.
 */
@RestController
@RequestMapping("/api/auth")
@Tag(name = "Authentication")
public class AuthController {

    private final AuthService authService;
    private final JwtService jwtService;
    private final SecurityProperties securityProperties;

    public AuthController(AuthService authService, JwtService jwtService,
                          SecurityProperties securityProperties) {
        this.authService = authService;
        this.jwtService = jwtService;
        this.securityProperties = securityProperties;
    }

    @PostMapping("/login")
    @Operation(summary = "Sign in with a User ID and password")
    public ResponseEntity<AuthDtos.LoginResponse> login(@Valid @RequestBody AuthDtos.LoginRequest body,
                                                        HttpServletRequest request) {
        AuthService.IssuedTokens issued = authService.login(body, clientIp(request));
        // The raw refresh token is sealed into the HttpOnly cookie, never into the body.
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, refreshCookie(issued.refreshToken()))
                .body(issued.response());
    }

    @PostMapping("/refresh")
    @Operation(summary = "Rotate the refresh cookie and mint a new access token")
    public ResponseEntity<AuthDtos.LoginResponse> refresh(
            @CookieValue(name = "${customer-trace.security.refresh-cookie-name:ct_refresh}",
                    required = false) String refreshToken,
            HttpServletRequest request) {
        AuthService.IssuedTokens issued = authService.refresh(refreshToken, clientIp(request));
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, refreshCookie(issued.refreshToken()))
                .body(issued.response());
    }

    @PostMapping("/logout")
    @Operation(summary = "Revoke the current session")
    public ResponseEntity<Void> logout(
            @CookieValue(name = "${customer-trace.security.refresh-cookie-name:ct_refresh}",
                    required = false) String refreshToken,
            HttpServletRequest request) {
        authService.logout(refreshToken, clientIp(request));
        // Always clear the cookie, even if the token was already gone.
        ResponseCookie clear = ResponseCookie.from(securityProperties.getRefreshCookieName(), "")
                .httpOnly(true)
                .secure(securityProperties.isRefreshCookieSecure())
                .path("/api/auth")
                .maxAge(0)
                .sameSite(securityProperties.getRefreshCookieSameSite())
                .build();
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, clear.toString())
                .build();
    }

    @GetMapping("/me")
    @Operation(summary = "The authenticated principal")
    public ResponseEntity<AuthDtos.CurrentUserResponse> me(
            @org.springframework.security.core.annotation.AuthenticationPrincipal
            CurrentUser principal) {
        return ResponseEntity.ok(authService.currentUser(principal.userId()));
    }

    /**
     * Builds the refresh cookie and returns it ready for the {@code Set-Cookie} header.
     *
     * <p>Must be {@code build()}d: the builder's own {@code toString()} is an object identity,
     * not a header value, and produces an {@code Invalid Set-Cookie header} error.
     */
    private String refreshCookie(String value) {
        return ResponseCookie.from(securityProperties.getRefreshCookieName(), value)
                .httpOnly(true)
                .secure(securityProperties.isRefreshCookieSecure())
                .path("/api/auth")
                .maxAge(securityProperties.getRefreshTokenTtl())
                .sameSite(securityProperties.getRefreshCookieSameSite())
                .build()
                .toString();
    }

    /**
     * Best-effort client address for the audit log.
     *
     * <p>Never used for an authorization decision.
     */
    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}