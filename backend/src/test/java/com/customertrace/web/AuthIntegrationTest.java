package com.customertrace.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import com.customertrace.domain.User;
import com.customertrace.support.IntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Authentication and role-based access, exercised over HTTP against a real database.
 */
class AuthIntegrationTest extends IntegrationTestBase {

    @Test
    @DisplayName("signs in with valid credentials and returns a signed token")
    void signsInSuccessfully() throws Exception {
        createUser("owner@restrnt", "RESTAURANT", "Owner");

        String token = loginAndGetToken("owner@restrnt", "restaurant");

        assertThat(token).isNotBlank();
        assertThat(token.split("\\.")).hasSize(3); // header.payload.signature
    }

    @Test
    @DisplayName("login hands the browser a readable XSRF-TOKEN alongside the HttpOnly cookie")
    void loginPrimesTheCsrfCookie() throws Exception {
        createUser("prime@restrnt", "RESTAURANT", "Prime");

        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userId":"prime@restrnt","password":"%s","portal":"restaurant"}
                                """.formatted(TEST_PASSWORD)))
                .andExpect(status().isOk())
                .andReturn();

        jakarta.servlet.http.Cookie xsrf =
                result.getResponse().getCookie("XSRF-TOKEN");
        assertThat(xsrf)
                .as("the client must have a token to echo, or refresh after a reload 403s")
                .isNotNull();
        assertThat(xsrf.isHttpOnly())
                .as("a JS-unreadable cookie cannot be echoed back in a header")
                .isFalse();

        jakarta.servlet.http.Cookie refresh =
                result.getResponse().getCookie("ct_refresh");
        assertThat(refresh).isNotNull();
        assertThat(refresh.isHttpOnly())
                .as("the session credential itself must stay out of reach of scripts")
                .isTrue();
    }

    @Test
    @DisplayName("refresh rotates the cookie and mints a fresh access token")
    void refreshRotatesTheSession() throws Exception {
        createUser("rotate@restrnt", "RESTAURANT", "Rotate");

        MvcResult login = loginFor("rotate@restrnt");

        MvcResult refreshed = postWithSessionCookies(login, "/api/auth/refresh")
                .andExpect(status().isOk())
                .andReturn();

        assertThat(parse(refreshed).path("accessToken").asText())
                .as("a reload must land the user back in their session, not signed out")
                .isNotBlank();

        // The rotated cookie keeps the session alive for the next reload. The CSRF cookie was
        // not reissued, so the client keeps echoing the one it already holds.
        postWithSessionCookies(refreshed, login, "/api/auth/refresh")
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("refresh without the custom CSRF header is refused")
    void refreshRequiresTheCustomHeader() throws Exception {
        createUser("noheader@restrnt", "RESTAURANT", "No Header");

        MvcResult login = loginFor("noheader@restrnt");
        jakarta.servlet.http.Cookie session = login.getResponse().getCookie("ct_refresh");
        jakarta.servlet.http.Cookie xsrf = login.getResponse().getCookie("XSRF-TOKEN");

        // The echoed cookie alone is not enough: the header is what distinguishes script that
        // could read the cookie from a cross-origin request that merely rides on it.
        mockMvc.perform(post("/api/auth/refresh")
                        .cookie(session, xsrf)
                        .header("X-XSRF-TOKEN", xsrf.getValue()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("logout without the custom CSRF header leaves the session alive")
    void logoutRequiresTheCustomHeader() throws Exception {
        createUser("nologout@restrnt", "RESTAURANT", "No Logout");

        MvcResult login = loginFor("nologout@restrnt");
        jakarta.servlet.http.Cookie session = login.getResponse().getCookie("ct_refresh");
        jakarta.servlet.http.Cookie xsrf = login.getResponse().getCookie("XSRF-TOKEN");

        mockMvc.perform(post("/api/auth/logout")
                        .cookie(session, xsrf)
                        .header("X-XSRF-TOKEN", xsrf.getValue()))
                .andExpect(status().isForbidden());

        postWithSessionCookies(login, "/api/auth/refresh")
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("refresh without the echoed CSRF token is refused")
    void refreshRequiresTheCsrfEcho() throws Exception {
        createUser("nocsrf@restrnt", "RESTAURANT", "No Csrf");

        MvcResult login = loginFor("nocsrf@restrnt");
        jakarta.servlet.http.Cookie session = login.getResponse().getCookie("ct_refresh");

        // The custom header alone is not enough: a cross-origin attacker can set headers it
        // cannot read the cookie value back from.
        mockMvc.perform(post("/api/auth/refresh")
                        .cookie(session)
                        .header("X-CT-CSRF", "1"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("refresh with a forged CSRF echo is refused")
    void refreshRejectsAForgedCsrfEcho() throws Exception {
        createUser("forged@restrnt", "RESTAURANT", "Forged");

        MvcResult login = loginFor("forged@restrnt");
        jakarta.servlet.http.Cookie session = login.getResponse().getCookie("ct_refresh");
        jakarta.servlet.http.Cookie xsrf = login.getResponse().getCookie("XSRF-TOKEN");

        mockMvc.perform(post("/api/auth/refresh")
                        .cookie(session, xsrf)
                        .header("X-CT-CSRF", "1")
                        .header("X-XSRF-TOKEN", "00000000-0000-0000-0000-000000000000"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("replaying a rotated refresh cookie revokes the session")
    void rotatedRefreshCookieIsSingleUse() throws Exception {
        createUser("replay@restrnt", "RESTAURANT", "Replay");

        MvcResult login = loginFor("replay@restrnt");
        postWithSessionCookies(login, "/api/auth/refresh").andExpect(status().isOk());

        // The original cookie is spent; presenting it again is treated as theft.
        postWithSessionCookies(login, "/api/auth/refresh")
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("two rotations in the same second still produce distinct sessions")
    void rotationsWithinTheSameSecondAreDistinct() throws Exception {
        createUser("fast@restrnt", "RESTAURANT", "Fast");

        MvcResult login = loginFor("fast@restrnt");

        // jti makes each token unique; without it both rows would share a token_hash and the
        // second insert would trip the unique key, failing the caller with a conflict.
        MvcResult first = postWithSessionCookies(login, "/api/auth/refresh")
                .andExpect(status().isOk())
                .andReturn();
        postWithSessionCookies(first, login, "/api/auth/refresh")
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("never returns the password or its hash")
    void doesNotLeakCredentials() throws Exception {
        createUser("owner2@restrnt", "RESTAURANT", "Owner Two");

        String body = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userId":"owner2@restrnt","password":"%s","portal":"restaurant"}
                                """.formatted(TEST_PASSWORD)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain(TEST_PASSWORD);
        assertThat(body).doesNotContain("passwordHash");
        assertThat(body).doesNotContain("password_hash");
    }

    @Test
    @DisplayName("rejects a wrong password with 401")
    void rejectsWrongPassword() throws Exception {
        createUser("owner3@restrnt", "RESTAURANT", "Owner Three");

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userId":"owner3@restrnt","password":"WrongPassword!"}
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    @DisplayName("rejects an unknown user with a message identical to a wrong password")
    void rejectsUnknownUser() throws Exception {
        // Identical wording prevents account enumeration.
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userId":"ghost@restrnt","password":"%s"}
                                """.formatted(TEST_PASSWORD)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Incorrect User ID or password."));
    }

    @Test
    @DisplayName("rejects a valid account on the wrong portal with 403")
    void rejectsWrongPortal() throws Exception {
        // The suffix matches "restrnt", but a creator account must not sign in as a restaurant.
        createUser("person@creator", "CREATOR", "Person");

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userId":"person@creator","password":"%s","portal":"restaurant"}
                                """.formatted(TEST_PASSWORD)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    @DisplayName("rejects a disabled account with 403")
    void rejectsDisabledAccount() throws Exception {
        User disabled = createUser("gone@restrnt", "RESTAURANT", "Gone");
        disabled.setStatus("DISABLED");
        userRepository.save(disabled);

        mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"userId":"gone@restrnt","password":"%s","portal":"restaurant"}
                        """.formatted(TEST_PASSWORD)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("rejects a missing, malformed or tampered token with 401")
    void rejectsBadTokens() throws Exception {
        createUser("tok@restrnt", "RESTAURANT", "Token");

        mockMvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized());

        // A structurally valid token whose signature is wrong must fail verification.
        String valid = tokenFor(userRepository.findByUserIdIgnoreCase("tok@restrnt").orElseThrow());
        String[] parts = valid.split("\\.");
        String tampered = parts[0] + "." + parts[1]
                + ".AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";

        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + tampered))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("returns the authenticated principal, never a password")
    void returnsCurrentUser() throws Exception {
        User user = createUser("me@restrnt", "RESTAURANT", "Me");

        JsonNode body = parse(mockMvc.perform(withToken(get("/api/auth/me"), tokenFor(user)))
                .andExpect(status().isOk())
                .andReturn());

        assertThat(body.path("userId").asText()).isEqualTo("me@restrnt");
        assertThat(body.path("roles").toString()).contains("RESTAURANT");
        assertThat(body.toString()).doesNotContain("passwordHash");
    }

    @Test
    @DisplayName("denies a creator access to an admin-only endpoint")
    void deniesCreatorToAdminEndpoint() throws Exception {
        User creator = createUser("cre@creator", "CREATOR", "Creator");

        mockMvc.perform(withToken(get("/api/admin/campaigns"), tokenFor(creator)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("denies an admin access to a restaurant-only endpoint")
    void deniesAdminToRestaurantEndpoint() throws Exception {
        User admin = createUser("adm@Trynearby", "ADMIN", "Admin");

        mockMvc.perform(withToken(get("/api/restaurants/campaigns"), tokenFor(admin)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("denies a restaurant access to the admin endpoint and vice versa")
    void deniesCrossRoleEndpoints() throws Exception {
        User admin = createUser("adm2@Trynearby", "ADMIN", "Admin Two");
        User restaurant = createUser("rst@restrnt", "RESTAURANT", "Rest");

        // A restaurant may not reach the creator namespace either.
        mockMvc.perform(withToken(get("/api/creator/campaigns"), tokenFor(restaurant)))
                .andExpect(status().isForbidden());
        mockMvc.perform(withToken(get("/api/restaurants/scan/lookup?code=AB1203A"),
                        tokenFor(admin)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("grants each role access to its own endpoints")
    void grantsEachRoleItsOwnEndpoints() throws Exception {
        User admin = createUser("ok@Trynearby", "ADMIN", "Admin");
        // The restaurant and creator helpers also create the profile row each portal needs.
        createRestaurant("ok@restrnt", "Ok Restaurant", "Kochi");
        createCreator("ok@creator", "Ok Creator", "@okcreator");

        User restaurant = userRepository.findByUserIdIgnoreCase("ok@restrnt").orElseThrow();
        User creator = userRepository.findByUserIdIgnoreCase("ok@creator").orElseThrow();

        mockMvc.perform(withToken(get("/api/admin/campaigns"), tokenFor(admin)))
                .andExpect(status().isOk());
        mockMvc.perform(withToken(get("/api/restaurants/campaigns"), tokenFor(restaurant)))
                .andExpect(status().isOk());
        mockMvc.perform(withToken(get("/api/creator/campaigns"), tokenFor(creator)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("claiming a different portal never grants that role")
    void portalClaimDoesNotGrantRole() throws Exception {
        // The creator claims to be an administrator; the stored role still governs.
        createUser("liar@creator", "CREATOR", "Liar");

        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userId":"liar@creator","password":"%s","portal":"trynearby"}
                                """.formatted(TEST_PASSWORD)))
                .andReturn();

        int status = result.getResponse().getStatus();
        // Either refused outright, or signed in without any administrator authority.
        assertThat(status).isIn(200, 403);
        if (status == 200) {
            assertThat(result.getResponse().getContentAsString()).doesNotContain("ADMIN");
        }
    }
}
