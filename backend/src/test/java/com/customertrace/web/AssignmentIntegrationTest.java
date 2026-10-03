package com.customertrace.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import com.customertrace.domain.Creator;
import com.customertrace.domain.Restaurant;
import com.customertrace.domain.User;
import com.customertrace.repository.NotificationRepository;
import com.customertrace.support.IntegrationTestBase;

/**
 * Creator assignment lifecycle.
 *
 * <p>Covers duplicate prevention, deactivation, cross-restaurant denial and the notifications
 * raised on each status change.
 */
class AssignmentIntegrationTest extends IntegrationTestBase {

    @Autowired
    private NotificationRepository notifications;

    private User admin;
    private Restaurant kitchen;
    private Restaurant other;
    private User kitchenUser;
    private User otherUser;
    private Creator star;

    @BeforeEach
    void setUp() {
        admin = createUser("admin-assign@Trynearby", "ADMIN", "Admin");
        kitchen = createRestaurant("kitchen-assign@restrnt", "Kitchen", "Kochi");
        other = createRestaurant("other-assign@restrnt", "Other Kitchen", "Kochi");
        createCreator("star-assign@creator", "Star", "@star-assign");
        kitchenUser = userRepository.findByUserIdIgnoreCase("kitchen-assign@restrnt").orElseThrow();
        otherUser = userRepository.findByUserIdIgnoreCase("other-assign@restrnt").orElseThrow();
        star = creatorRepository.findAll().stream()
                .filter(c -> "@star-assign".equals(c.getHandle())).findFirst().orElseThrow();
    }

    /** Builds the assign body; ids are stringified so callers may pass boxed or literal values. */
    private String assignBody(Object restaurantId, Object creatorId) {
        return "{\"restaurantId\":" + restaurantId + ",\"creatorId\":" + creatorId + "}";
    }

    @Test
    @DisplayName("a restaurant assigns a creator to itself and both parties are notified")
    void restaurantAssignsCreator() throws Exception {
        mockMvc.perform(withToken(post("/api/restaurant/assignments"), tokenFor(kitchenUser))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(assignBody("0", star.getId())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.restaurantId").value(kitchen.getId()))
                .andExpect(jsonPath("$.creatorId").value(star.getId()))
                .andExpect(jsonPath("$.active").value(true));
        flush();

        assertThat(notifications.countUnread(kitchenUser.getId())).isEqualTo(1);
    }

    @Test
    @DisplayName("the creator sees the new assignment on their profile")
    void creatorSeesAssignment() throws Exception {
        mockMvc.perform(withToken(post("/api/restaurant/assignments"), tokenFor(kitchenUser))
                        .contentType(MediaType.APPLICATION_JSON).content(assignBody("0", star.getId())))
                .andExpect(status().isCreated());
        User starUser = userRepository.findByUserIdIgnoreCase("star-assign@creator").orElseThrow();

        mockMvc.perform(withToken(get("/api/creator/assignments"), tokenFor(starUser)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].restaurantId").value(kitchen.getId()));
    }

    @Test
    @DisplayName("a duplicate active assignment is refused")
    void duplicateAssignmentRefused() throws Exception {
        mockMvc.perform(withToken(post("/api/restaurant/assignments"), tokenFor(kitchenUser))
                        .contentType(MediaType.APPLICATION_JSON).content(assignBody("0", star.getId())))
                .andExpect(status().isCreated());

        mockMvc.perform(withToken(post("/api/restaurant/assignments"), tokenFor(kitchenUser))
                        .contentType(MediaType.APPLICATION_JSON).content(assignBody("0", star.getId())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ASSIGNMENT_EXISTS"));
    }

    @Test
    @DisplayName("assigning an unknown creator is a 404")
    void unknownCreatorRefused() throws Exception {
        mockMvc.perform(withToken(post("/api/restaurant/assignments"), tokenFor(kitchenUser))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(assignBody("0", 999999)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("a restaurant cannot assign a creator to another restaurant")
    void crossRestaurantAssignDenied() throws Exception {
        mockMvc.perform(withToken(post("/api/admin/assignments"), tokenFor(kitchenUser))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(assignBody(other.getId().toString(), star.getId().toString())))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("a creator cannot assign itself to a restaurant")
    void creatorCannotAssign() throws Exception {
        User starUser = userRepository.findByUserIdIgnoreCase("star-assign@creator").orElseThrow();
        mockMvc.perform(withToken(post("/api/restaurant/assignments"), tokenFor(starUser))
                        .contentType(MediaType.APPLICATION_JSON).content(assignBody("0", star.getId())))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("a restaurant cannot see or deactivate another restaurant's assignment")
    void crossRestaurantDeactivateDenied() throws Exception {
        // Administrator creates the assignment against a different restaurant.
        String foreignId = createdAssignmentId(tokenFor(admin), other.getId());

        mockMvc.perform(withToken(get("/api/restaurant/assignments"), tokenFor(kitchenUser)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        // The assignment is invisible to the other restaurant: 404, so ids cannot be probed.
        mockMvc.perform(withToken(delete("/api/restaurant/assignments/" + foreignId),
                        tokenFor(kitchenUser)))
                .andExpect(status().isNotFound());

        // And the admin route stays closed to restaurants.
        mockMvc.perform(withToken(delete("/api/admin/assignments/" + foreignId),
                        tokenFor(kitchenUser)))
                .andExpect(status().isForbidden());
        flush();

        // The assignment is untouched by both attempts.
        mockMvc.perform(withToken(get("/api/restaurant/assignments"), tokenFor(otherUser)))
                .andExpect(jsonPath("$[0].active").value(true));
    }

    @Test
    @DisplayName("deactivating records the status and notifies the creator")
    void deactivateUpdatesStatus() throws Exception {
        User starUser = userRepository.findByUserIdIgnoreCase("star-assign@creator").orElseThrow();
        long before = notifications.countUnread(starUser.getId());

        String id = assignedAsRestaurant(tokenFor(kitchenUser));

        mockMvc.perform(withToken(delete("/api/restaurant/assignments/" + id),
                        tokenFor(kitchenUser)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));
        flush();

        assertThat(notifications.countUnread(starUser.getId())).isEqualTo(before + 2);
    }

    @Test
    @DisplayName("deactivating twice is a conflict and does not notify twice")
    void deactivateTwiceRefused() throws Exception {
        User starUser = userRepository.findByUserIdIgnoreCase("star-assign@creator").orElseThrow();
        String id = assignedAsRestaurant(tokenFor(kitchenUser));

        mockMvc.perform(withToken(delete("/api/restaurant/assignments/" + id),
                        tokenFor(kitchenUser))).andExpect(status().isOk());
        flush();
        long after = notifications.countUnread(starUser.getId());

        mockMvc.perform(withToken(delete("/api/restaurant/assignments/" + id),
                        tokenFor(kitchenUser)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ASSIGNMENT_INACTIVE"));
        flush();
        assertThat(notifications.countUnread(starUser.getId())).isEqualTo(after);
    }

    @Test
    @DisplayName("an administrator can reactivate a deactivated assignment")
    void reactivateRestoresAssignment() throws Exception {
        String id = parse(mockMvc.perform(withToken(post("/api/admin/assignments"),
                        tokenFor(admin)).contentType(MediaType.APPLICATION_JSON)
                .content(assignBody(other.getId().toString(), star.getId().toString())))
                .andReturn()).path("id").asText();

        mockMvc.perform(withToken(delete("/api/admin/assignments/" + id), tokenFor(admin)))
                .andExpect(status().isOk());
        mockMvc.perform(withToken(post("/api/admin/assignments/" + id + "/reactivate"),
                        tokenFor(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(true));

        // Reactivating an already-active row is refused.
        mockMvc.perform(withToken(post("/api/admin/assignments/" + id + "/reactivate"),
                        tokenFor(admin)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ASSIGNMENT_ACTIVE"));
    }

    @Test
    @DisplayName("unknown assignment ids are 404")
    void unknownAssignment() throws Exception {
        mockMvc.perform(withToken(delete("/api/restaurant/assignments/999999"),
                        tokenFor(kitchenUser)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("assignment endpoints require authentication")
    void requiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/restaurant/assignments")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/creator/assignments")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/admin/assignments")).andExpect(status().isUnauthorized());
        mockMvc.perform(delete("/api/restaurant/assignments/1"))
                .andExpect(status().isUnauthorized());
    }

    /**
     * Assigns {@link #star} to {@code restaurantId} as {@code actorToken} and returns the new id.
     *
     * <p>Uses the restaurant route, so the caller must be that restaurant's own user.
     */
    private String assignedAsRestaurant(String actorToken) throws Exception {
        return parse(mockMvc.perform(withToken(post("/api/restaurant/assignments"), actorToken)
                        .contentType(MediaType.APPLICATION_JSON).content(assignBody(0, star.getId())))
                .andExpect(status().isCreated()).andReturn()).path("id").asText();
    }

    /** Assigns {@link #star} to {@code restaurantId} as an administrator and returns the new id. */
    private String createdAssignmentId(String token, Long restaurantId) throws Exception {
        return parse(mockMvc.perform(withToken(post("/api/admin/assignments"), token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(assignBody(restaurantId, star.getId())))
                .andReturn()).path("id").asText();
    }
}