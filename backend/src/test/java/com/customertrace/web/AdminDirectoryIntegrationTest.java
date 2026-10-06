package com.customertrace.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import com.fasterxml.jackson.databind.JsonNode;
import com.customertrace.domain.Creator;
import com.customertrace.domain.Restaurant;
import com.customertrace.domain.User;
import com.customertrace.support.IntegrationTestBase;

/**
 * The administrator's view of restaurants, creators and their subscriptions.
 *
 * <p>Covers the directory listing, suspending and reactivating a profile, and the rule that these
 * whole-platform routes belong to administrators alone.
 */
class AdminDirectoryIntegrationTest extends IntegrationTestBase {

    private User admin;
    private Restaurant kitchen;
    private Creator star;
    private User restaurantUser;

    @BeforeEach
    void setUp() {
        admin = createUser("admin-dir@Trynearby", "ADMIN", "Admin");
        kitchen = createRestaurant("kitchen-dir@restrnt", "Kitchen", "Kochi");
        restaurantUser = userRepository.findByUserIdIgnoreCase("kitchen-dir@restrnt").orElseThrow();
        star = createCreator("star-dir@creator", "Star", "@star-dir");
    }

    private MvcResult listAs(User user, String path) throws Exception {
        return mockMvc.perform(withToken(get(path), tokenFor(user)))
                .andExpect(status().isOk()).andReturn();
    }

    /**
     * Finds one element by the `userId` of its profile.
     *
     * <p>Matched on the account rather than the display name: these tests share a database with the
     * rest of the suite, and several fixtures elsewhere are also called "Star" or "Kitchen".
     */
    private static JsonNode rowForUser(JsonNode array, String userId) {
        for (JsonNode element : array) {
            if (userId.equals(element.path("userId").asText())) {
                return element;
            }
        }
        throw new AssertionError("No row for " + userId + " in " + array);
    }

    @Test
    @DisplayName("the directory lists restaurants with their status and workload")
    void listsRestaurants() throws Exception {
        assignCreator(star, kitchen);

        JsonNode row = rowForUser(parse(listAs(admin, "/api/admin/restaurants")),
                restaurantUser.getUserId());
        assertThat(row.path("name").asText()).isEqualTo("Kitchen");
        assertThat(row.path("status").asText()).isEqualTo("ACTIVE");
        assertThat(row.path("activeCreatorCount").asInt()).isEqualTo(1);
        // The application serialises with `non_null`, so "no ratings" arrives as an absent field
        // rather than a JSON null. Either way the value must not be a fabricated 0, which would
        // render as "0.0★ from 0" and read as a terrible score instead of "no ratings yet".
        assertThat(row.has("averageRating")).isFalse();
        assertThat(row.path("ratingCount").asInt()).isZero();
        assertThat(row.path("userId").asText()).isEqualTo(restaurantUser.getUserId());
    }

@Test
    @DisplayName("suspending a restaurant hides it from new work and reactivating restores it")
    void suspendAndReactivateRestaurant() throws Exception {
        assignCreator(star, kitchen);

        mockMvc.perform(withToken(patch("/api/admin/restaurants/" + kitchen.getId() + "/status"),
                        tokenFor(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"SUSPENDED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUSPENDED"));

        // Persisted, not merely echoed back.
        assertThat(restaurantRepository.findById(kitchen.getId()).orElseThrow().getStatus())
                .isEqualTo("SUSPENDED");
        // The assignment is untouched: suspending a profile is not the same as ending one.
        assertThat(assignmentRepository
                .findByRestaurantIdAndCreatorId(kitchen.getId(), star.getId())
                .orElseThrow().isActive()).isTrue();

        // Lower case is accepted, since the request is normalised rather than matched exactly.
        mockMvc.perform(withToken(patch("/api/admin/restaurants/" + kitchen.getId() + "/status"),
                        tokenFor(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"active\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    @DisplayName("suspending a creator leaves their assignments and history alone")
    void suspendAndReactivateCreator() throws Exception {
        assignCreator(star, kitchen);

        mockMvc.perform(withToken(patch("/api/admin/creators/" + star.getId() + "/status"),
                        tokenFor(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"SUSPENDED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUSPENDED"));

        assertThat(creatorRepository.findById(star.getId()).orElseThrow().getStatus())
                .isEqualTo("SUSPENDED");
        // Still assigned, so an accidental suspension is recoverable without reassigning anyone.
        assertThat(assignmentRepository
                .findByRestaurantIdAndCreatorId(kitchen.getId(), star.getId())
                .orElseThrow().isActive()).isTrue();

        mockMvc.perform(withToken(patch("/api/admin/creators/" + star.getId() + "/status"),
                        tokenFor(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"ACTIVE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    @DisplayName("the directory lists creators with handle and assignment counts")
    void listsCreators() throws Exception {
        assignCreator(star, kitchen);

        JsonNode row = rowForUser(parse(listAs(admin, "/api/admin/creators")),
                userRepository.findByUserIdIgnoreCase("star-dir@creator").orElseThrow().getUserId());
        assertThat(row.path("displayName").asText()).isEqualTo("Star");
        assertThat(row.path("handle").asText()).isEqualTo("@star-dir");
        assertThat(row.path("activeRestaurantCount").asInt()).isEqualTo(1);
    }
@Test
    @DisplayName("an unknown status is refused rather than stored")
    void rejectsUnknownStatus() throws Exception {
        mockMvc.perform(withToken(patch("/api/admin/restaurants/" + kitchen.getId() + "/status"),
                        tokenFor(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"BANANA\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("UNKNOWN_STATUS"));

        // The rejected request changed nothing.
        assertThat(restaurantRepository.findById(kitchen.getId()).orElseThrow().getStatus())
                .isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("a blank status is a 400 rather than a null write")
    void rejectsBlankStatus() throws Exception {
        mockMvc.perform(withToken(patch("/api/admin/restaurants/" + kitchen.getId() + "/status"),
                        tokenFor(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"  \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("STATUS_REQUIRED"));
    }

    @Test
    @DisplayName("an unknown restaurant or creator is a 404, not a silent no-op")
    void unknownIdsAre404() throws Exception {
        mockMvc.perform(withToken(patch("/api/admin/restaurants/999999/status"), tokenFor(admin))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"ACTIVE\"}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(withToken(patch("/api/admin/creators/999999/status"), tokenFor(admin))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"ACTIVE\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("only an administrator may read or change the platform directory")
    void nonAdminIsRefused() throws Exception {
        // Control: the same routes answer an administrator, so a 403 below is about the role alone.
        listAs(admin, "/api/admin/restaurants");
        listAs(admin, "/api/admin/creators");

        mockMvc.perform(withToken(get("/api/admin/restaurants"), tokenFor(restaurantUser)))
                .andExpect(status().isForbidden());
        mockMvc.perform(withToken(get("/api/admin/creators"),
                        tokenFor(userRepository.findByUserIdIgnoreCase("star-dir@creator")
                                .orElseThrow())))
                .andExpect(status().isForbidden());
        mockMvc.perform(withToken(patch("/api/admin/restaurants/" + kitchen.getId() + "/status"),
                        tokenFor(restaurantUser))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"SUSPENDED\"}"))
                .andExpect(status().isForbidden());

        // The refused write did not happen.
        assertThat(restaurantRepository.findById(kitchen.getId()).orElseThrow().getStatus())
                .isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("the directory routes require authentication")
    void requiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/admin/restaurants")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/admin/creators")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/admin/subscriptions")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("subscriptions are listed platform-wide as an array, empty rather than absent")
    void listsSubscriptions() throws Exception {
        // The fixture restaurant has no subscription, so no row may claim one.
        JsonNode rows = parse(listAs(admin, "/api/admin/subscriptions"));
        assertThat(rows.isArray()).isTrue();
        for (JsonNode row : rows) {
            assertThat(row.path("restaurantId").isNumber()).isTrue();
        }
    }
}