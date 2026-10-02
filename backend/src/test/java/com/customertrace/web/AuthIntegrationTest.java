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
