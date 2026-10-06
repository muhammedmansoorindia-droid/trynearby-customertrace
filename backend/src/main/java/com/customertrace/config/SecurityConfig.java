package com.customertrace.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import java.util.List;
import java.util.Optional;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import com.customertrace.security.JwtAuthenticationFilter;
import com.customertrace.security.SecurityProperties;

/**
 * Security configuration.
 *
 * <p><b>Token strategy.</b> Short-lived (15 min) JWT access tokens travel in the
 * {@code Authorization} header and are held in memory by the frontend. The long-lived refresh
 * token is delivered as an <b>HttpOnly</b> cookie that page scripts cannot read, and is stored
 * server-side only as a BCrypt hash. This avoids the long-lived credential in localStorage that a
 * pure localStorage design would create.
 *
 * <p><b>CSRF.</b> The access token is not an ambient credential — a cross-site form post cannot
 * make the browser attach it — so the cookie-borne endpoints are the only CSRF surface. The
 * session is {@code STATELESS}; the only cookie-authenticated routes are refresh and logout,
 * which additionally require a custom {@code X-CT-CSRF} header that a cross-origin form post
 * cannot forge.
 *
 * <p><b>Errors.</b> Unauthenticated requests return 401 and unauthorized ones 403, both as
 * JSON, so the frontend can distinguish "session expired, refresh" from "not permitted".
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@EnableConfigurationProperties({SecurityProperties.class, CorsProperties.class})
public class SecurityConfig {

    /** Header the frontend must send on cookie-authenticated calls. */
    public static final String CSRF_HEADER = "X-CT-CSRF";

    private final JwtAuthenticationFilter jwtFilter;
    private final SecurityProperties securityProperties;
    private final CorsProperties corsProperties;

    public SecurityConfig(JwtAuthenticationFilter jwtFilter, SecurityProperties securityProperties,
                          CorsProperties corsProperties) {
        this.jwtFilter = jwtFilter;
        this.securityProperties = securityProperties;
        this.corsProperties = corsProperties;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            // No server-side session and no login form: every request is authenticated by the
            // bearer token or the refresh cookie alone.
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .csrf(csrf -> csrf
                .csrfTokenRepository(xsrfTokenRepository())
                // The API never reads the CSRF cookie; the custom header below is the check.
                .csrfTokenRequestHandler(
                        new org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler())
                .ignoringRequestMatchers(r -> !isCookieAuthenticated(r)))
            // Login is exempt from the CSRF *check* (it has no cookie yet) but still hands the
            // browser a readable XSRF-TOKEN, so the first refresh after login can echo it back.
            // The X-CT-CSRF half of the check is enforced by the filter below.
            .addFilterAfter(new CookieAuthCsrfFilter(xsrfTokenRepository()),
                    org.springframework.security.web.csrf.CsrfFilter.class)
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .authorizeHttpRequests(auth -> auth
                // Public: login, refresh/logout (cookie + custom header), health, API docs.
                .requestMatchers("/api/auth/login", "/api/auth/refresh", "/api/auth/logout",
                        "/actuator/health/**", "/actuator/info",
                        "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                .requestMatchers("/api/admin/**").hasRole("ADMIN")
                // Creator discovery is available to restaurants and admin only.
                // Ant-style MVC patterns do not allow "**" in the middle of a path, so the
                // sub-resources use a trailing wildcard only.
                .requestMatchers("/api/restaurants/*/creators", "/api/restaurants/*/creators/**")
                    .hasAnyRole("RESTAURANT", "ADMIN")
                // A restaurant's public review profile is readable by any signed-in user, so it must be
                // matched before the broader /api/restaurants/** restaurant-only rule below.
                .requestMatchers("/api/restaurants/*/reviews").authenticated()
                .requestMatchers("/api/restaurants/**").hasRole("RESTAURANT")
                .requestMatchers("/api/creator/**").hasRole("CREATOR")
                .requestMatchers("/api/auth/me", "/api/notifications/**").authenticated()
                // Reports are role-scoped: a creator must never reach the platform-wide view,
                // and a restaurant must never reach the creator view. The service also enforces
                // ownership, but the URL namespace is the first line of defence.
                .requestMatchers("/api/admin/**").hasRole("ADMIN")
                .requestMatchers("/api/reports/platform/**").hasRole("ADMIN")
                .requestMatchers("/api/reports/restaurant/**").hasRole("RESTAURANT")
                .requestMatchers("/api/reports/creator/**").hasRole("CREATOR")
                .requestMatchers("/api/**").authenticated()
                .anyRequest().permitAll())
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED))
                .accessDeniedHandler((req, res, e) -> {
                    res.setStatus(HttpStatus.FORBIDDEN.value());
                    res.setContentType(MediaType.APPLICATION_JSON_VALUE);
                    res.getWriter().write("{\"status\":403,\"error\":\"Forbidden\","
                            + "\"message\":\"You do not have permission to perform this "
                            + "action.\"}");
                }))
            // Deny framing and content sniffing.
            .headers(h -> h.frameOptions(f -> f.deny()).contentTypeOptions(Customizer.withDefaults()))
            .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    /**
     * The CSRF token store for the cookie-authenticated refresh/logout routes.
     *
     * <p>Spring Security's standard double-submit design: the token is written to a cookie the
     * frontend <b>can</b> read, and the frontend echoes it in {@code X-XSRF-TOKEN}. This is the
     * cookie-authentication counterpart of the {@code Authorization} header — the cookie alone
     * is an ambient credential, so the echo is what makes the request deliberate.
     *
     * <p><b>Not HttpOnly on purpose.</b> A JS-unreadable cookie would make the echo impossible
     * and permanently break refresh (every reload would sign the user out), while adding no
     * protection the missing cookie already provides. The long-lived secret inside
     * {@code ct_refresh} stays HttpOnly.
     */
    private static org.springframework.security.web.csrf.CookieCsrfTokenRepository
            xsrfTokenRepository() {
        // withHttpOnlyFalse() is the documented Spring Security 6 spelling of
        // setCookieHttpOnly(false); the frontend has to read the cookie to echo it back.
        return org.springframework.security.web.csrf.CookieCsrfTokenRepository.withHttpOnlyFalse();
    }

    /**
     * Enforces the custom header on the cookie-authenticated routes, and issues the readable
     * CSRF cookie on login.
     *
     * <p>Two separate checks run here, because either one alone is forgeable by a cross-origin
     * attacker: the cookie is an ambient credential the browser attaches on its own, while the
     * header proves the request came from script that could <em>read</em> that cookie. Sending the
     * header without echoing the cookie is what a cross-origin form post looks like.
     *
     * <p>Login is exempt from the checks — there is no session to protect yet — but it still hands
     * back the token, because the very next cookie-authenticated call is a refresh, and without a
     * token to echo that refresh would have nothing to send and would be refused.
     */
    static final class CookieAuthCsrfFilter
            extends org.springframework.web.filter.OncePerRequestFilter {

        private final org.springframework.security.web.csrf.CookieCsrfTokenRepository repository;

        CookieAuthCsrfFilter(
                org.springframework.security.web.csrf.CookieCsrfTokenRepository repository) {
            this.repository = repository;
        }

        @Override
        protected void doFilterInternal(jakarta.servlet.http.HttpServletRequest request,
                                        jakarta.servlet.http.HttpServletResponse response,
                                        jakarta.servlet.FilterChain chain)
                throws jakarta.servlet.ServletException, java.io.IOException {
            String path = request.getRequestURI();

            if (isCookieRoute(path)) {
                String header = request.getHeader(CSRF_HEADER);
                if (header == null || header.isBlank()) {
                    response.setStatus(jakarta.servlet.http.HttpServletResponse.SC_FORBIDDEN);
                    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                    response.getWriter().write("{\"status\":403,\"error\":\"Forbidden\","
                            + "\"message\":\"Missing " + CSRF_HEADER + " header.\"}");
                    return;
                }
            } else if (path != null && path.equals("/api/auth/login")) {
                issueCsrfCookie(request, response);
            }

            chain.doFilter(request, response);
        }

        /** True when the browser would attach the session cookie to this request on its own. */
        private boolean isCookieRoute(String path) {
            return path != null && (path.equals("/api/auth/refresh") || path.equals("/api/auth/logout"));
        }

        private void issueCsrfCookie(jakarta.servlet.http.HttpServletRequest request,
                                     jakarta.servlet.http.HttpServletResponse response) {
            boolean alreadyPrimed = java.util.Arrays.stream(
                            Optional.ofNullable(request.getCookies()).orElse(new
                                    jakarta.servlet.http.Cookie[0]))
                    .anyMatch(c -> "XSRF-TOKEN".equals(c.getName()));

            if (!alreadyPrimed) {
                // generateToken + saveToken is the 6.x API; saveGeneratedToken no longer exists.
                repository.saveToken(repository.generateToken(request), request, response);
            }
        }
    }

    /**
     * CSRF is enforced only on routes authenticated by the refresh cookie.
     *
     * <p>A request authenticated by the Authorization header carries no ambient credential, so
     * there is nothing for a cross-site attacker to ride on.
     */
    private boolean isCookieAuthenticated(jakarta.servlet.http.HttpServletRequest request) {
        String path = request.getRequestURI();
        return path != null && (path.equals("/api/auth/refresh") || path.equals("/api/auth/logout"));
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(corsProperties.getOriginList());
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        // "X-XSRF-TOKEN" carries the value of the readable CSRF cookie on the
        // cookie-authenticated refresh/logout calls (the double-submit pattern).
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", CSRF_HEADER,
                "X-XSRF-TOKEN", "X-Requested-With"));
        config.setExposedHeaders(List.of("Set-Cookie"));
        // Required so the refresh cookie is sent; never "*" with credentials.
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    public SecurityProperties getSecurityProperties() {
        return securityProperties;
    }
}
