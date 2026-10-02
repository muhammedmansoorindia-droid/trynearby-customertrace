package com.customertrace.security;

import java.io.IOException;
import java.util.List;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Authenticates each request from the {@code Authorization: Bearer …} access token.
 *
 * <p>The token's signature is verified by {@link JwtService} before any claim is trusted, and
 * the role authorities are derived only from that verified claim. The filter establishes an
 * authentication for the request; authorization decisions remain with the security config and
 * with ownership checks in the services, which re-read the principal from the database.
 *
 * <p>A missing or invalid token is deliberately <em>not</em> rejected here. The filter simply
 * leaves the context anonymous and lets the authorization rules produce a 401, which keeps
 * public endpoints (login, health, docs) reachable.
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String HEADER = "Authorization";
    private static final String PREFIX = "Bearer ";

    private final JwtService jwtService;

    public JwtAuthenticationFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader(HEADER);
        if (header != null && header.startsWith(PREFIX)
                && SecurityContextHolder.getContext().getAuthentication() == null) {
            String token = header.substring(PREFIX.length()).trim();
            jwtService.parse(token).ifPresent(this::authenticate);
        }
        chain.doFilter(request, response);
    }

    private void authenticate(Claims claims) {
        Object rawUserId = claims.get(JwtService.CLAIM_USER_ID);
        if (!(rawUserId instanceof Number userId)) {
            return;
        }
        String userIdName = claims.get(JwtService.CLAIM_USER_ID_NAME, String.class);
        Object rawRoles = claims.get(JwtService.CLAIM_ROLES);

        List<SimpleGrantedAuthority> authorities = new java.util.ArrayList<>();
        if (rawRoles instanceof List<?> roles) {
            for (Object role : roles) {
                if (role != null) {
                    authorities.add(new SimpleGrantedAuthority("ROLE_" + role.toString().toUpperCase()));
                }
            }
        }

        CurrentUser principal = new CurrentUser(userId.longValue(), userIdName, authorities);
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(principal, null, authorities);
        // Mark authenticated: the credentials are a verified JWT signature, not a password.
        authentication.setDetails(request.getRemoteAddr());
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }
}