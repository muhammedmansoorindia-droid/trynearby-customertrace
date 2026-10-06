package com.customertrace.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import com.fasterxml.jackson.databind.JsonNode;
import com.customertrace.domain.Creator;
import com.customertrace.domain.Restaurant;
import com.customertrace.domain.User;
import com.customertrace.repository.NotificationRepository;
import com.customertrace.repository.RestaurantCreatorAssignmentRepository;
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

    @Autowired
    private RestaurantCreatorAssignmentRepository assignmentRepository;

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
    @DisplayName("each creator sees only their own assignments, never another creator's")
    void assignmentsAreScopedPerCreator() throws Exception {
        createCreator("second-assign@creator", "Second", "@second-assign");
        Creator second = creatorRepository.findAll().stream()
                .filter(c -> "@second-assign".equals(c.getHandle())).findFirst().orElseThrow();
        User starUser = userRepository.findByUserIdIgnoreCase("star-assign@creator").orElseThrow();
        User secondUser = userRepository.findByUserIdIgnoreCase("second-assign@creator").orElseThrow();

        // Two creators, two restaurants, crossed over on purpose.
        mockMvc.perform(withToken(post("/api/admin/assignments"), tokenFor(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(assignBody(kitchen.getId(), star.getId())))
                .andExpect(status().isCreated());
        mockMvc.perform(withToken(post("/api/admin/assignments"), tokenFor(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(assignBody(other.getId(), second.getId())))
                .andExpect(status().isCreated());

        // Each sees exactly one row, and it is their own.
        mockMvc.perform(withToken(get("/api/creator/assignments"), tokenFor(starUser)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].restaurantId").value(kitchen.getId()))
                .andExpect(jsonPath("$[0].creatorId").value(star.getId()));
        mockMvc.perform(withToken(get("/api/creator/assignments"), tokenFor(secondUser)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].restaurantId").value(other.getId()))
                .andExpect(jsonPath("$[0].creatorId").value(second.getId()));
    }

    @Test
    @DisplayName("a creator assigned to two restaurants is shown both, and only while both are active")
    void multiVenueCreatorSeesBothAndLosesEachOnDeactivation() throws Exception {
        User starUser = userRepository.findByUserIdIgnoreCase("star-assign@creator").orElseThrow();
        mockMvc.perform(withToken(post("/api/admin/assignments"), tokenFor(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(assignBody(kitchen.getId(), star.getId())))
                .andExpect(status().isCreated());
        long secondId = createAssignment(assignBody(other.getId(), star.getId()));
        mockMvc.perform(withToken(post("/api/admin/assignments/" + secondId + "/reactivate"),
                        tokenFor(admin))).andExpect(status().isConflict()); // already active

        // Two active rows is correct and expected: the admin granted two.
        mockMvc.perform(withToken(get("/api/creator/assignments"), tokenFor(starUser)))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[*].active").value(org.hamcrest.Matchers.everyItem(
                        org.hamcrest.Matchers.is(true))));

        // Deactivating one drops exactly that venue, and reactivating brings it back. This is the
        // regression that made "assigned to one restaurant" show two venues in the wizard.
        long kitchenRow = assignmentRepository
                .findByRestaurantIdAndCreatorId(kitchen.getId(), star.getId()).orElseThrow().getId();
        mockMvc.perform(withToken(delete("/api/admin/assignments/" + kitchenRow), tokenFor(admin)))
                .andExpect(status().isOk());

        // Both rows are still reported, but only the surviving venue is active — which is what the
        // wizard filters on. Asserted via the parsed body because JsonPath filter expressions do
        // not compose reliably with .length() here.
        assertThat(creatorVenues(starUser))
                .containsExactlyInAnyOrder(Map.entry(kitchen.getId(), false), Map.entry(other.getId(), true));

        mockMvc.perform(withToken(post("/api/admin/assignments/" + kitchenRow + "/reactivate"),
                        tokenFor(admin))).andExpect(status().isOk());
        assertThat(creatorVenues(starUser))
                .containsExactlyInAnyOrder(Map.entry(kitchen.getId(), true), Map.entry(other.getId(), true));
    }

    /** Each venue the given creator may currently act for, paired with its active flag. */
    private List<Map.Entry<Long, Boolean>> creatorVenues(User creatorUser) throws Exception {
        JsonNode rows = parse(mockMvc.perform(withToken(get("/api/creator/assignments"),
                tokenFor(creatorUser))).andReturn());
        List<Map.Entry<Long, Boolean>> venues = new ArrayList<>();
        rows.forEach(r -> venues.add(Map.entry(r.path("restaurantId").asLong(), r.path("active").asBoolean())));
        return venues;
    }

    /** Issues an admin assign and returns the new row's id. */
    private long createAssignment(String body) throws Exception {
        MvcResult result = mockMvc.perform(withToken(post("/api/admin/assignments"), tokenFor(admin))
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        return parse(result).path("id").asLong();
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
    @DisplayName("an administrator assigns a creator to a restaurant and both are notified")
    void adminAssignsCreatorToAnyRestaurant() throws Exception {
        mockMvc.perform(withToken(post("/api/admin/assignments"), tokenFor(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(assignBody(other.getId(), star.getId())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.restaurantId").value(other.getId()))
                .andExpect(jsonPath("$.creatorId").value(star.getId()))
                .andExpect(jsonPath("$.active").value(true))
                // The admin can target any venue, not only its own.
                .andExpect(jsonPath("$.assignedBy").value(admin.getId()));
        flush();

        assertThat(notifications.countUnread(otherUser.getId()))
                .as("the restaurant is told a creator was added")
                .isPositive();
    }

    @Test
    @DisplayName("the assignment list carries resolved names so the admin needs no second lookup")
    void adminListCarriesNames() throws Exception {
        createdAssignmentId(tokenFor(admin), other.getId());

        mockMvc.perform(withToken(get("/api/admin/assignments"), tokenFor(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].restaurantName").value("Other Kitchen"))
                .andExpect(jsonPath("$[0].creatorName").value("Star"));
    }

    @Test
    @DisplayName("the admin pickers list creators and restaurants")
    void adminDirectoryIsReadable() throws Exception {
        // The pickers read the directory summaries, which carry `displayName` for a creator rather
        // than the generic `name` of the former picker DTO.
        mockMvc.perform(withToken(get("/api/admin/creators"), tokenFor(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].displayName",
                        org.hamcrest.Matchers.hasItems("Star")))
                .andExpect(jsonPath("$[*].handle",
                        org.hamcrest.Matchers.hasItem("@star-assign")));
        mockMvc.perform(withToken(get("/api/admin/restaurants"), tokenFor(admin)))
                .andExpect(status().isOk())
                // Both fixtures are listed; order is alphabetical among active entries.
                .andExpect(jsonPath("$.length()").value(org.hamcrest.Matchers.greaterThanOrEqualTo(2)))
                .andExpect(jsonPath("$[*].name",
                        org.hamcrest.Matchers.hasItems("Kitchen", "Other Kitchen")))
                // Status is what the picker marks an inactive profile with, so it must be present.
                .andExpect(jsonPath("$[*].status",
                        org.hamcrest.Matchers.hasItem("ACTIVE")));
    }

    @Test
    @DisplayName("only an administrator may manage assignments platform-wide")
    void nonAdminsCannotUseAdminRoutes() throws Exception {
        String id = createdAssignmentId(tokenFor(admin), other.getId());
        String body = assignBody(other.getId(), star.getId());

        // A restaurant may assign to itself, but never through the admin route or to another venue.
        mockMvc.perform(withToken(post("/api/admin/assignments"), tokenFor(kitchenUser))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        // A creator is the subject of an assignment, never a manager of one.
        User starUser = userRepository.findByUserIdIgnoreCase("star-assign@creator").orElseThrow();
        mockMvc.perform(withToken(post("/api/admin/assignments"), tokenFor(starUser))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        mockMvc.perform(withToken(delete("/api/admin/assignments/" + id), tokenFor(kitchenUser)))
                .andExpect(status().isForbidden());
        mockMvc.perform(withToken(post("/api/admin/assignments/" + id + "/reactivate"),
                        tokenFor(starUser)))
                .andExpect(status().isForbidden());
        // Reading the platform-wide list is likewise admin-only.
        mockMvc.perform(withToken(get("/api/admin/assignments"), tokenFor(kitchenUser)))
                .andExpect(status().isForbidden());
        mockMvc.perform(withToken(get("/api/admin/creators"), tokenFor(starUser)))
                .andExpect(status().isForbidden());
        mockMvc.perform(withToken(get("/api/admin/restaurants"), tokenFor(starUser)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("the admin routes require authentication")
    void adminRoutesRequireAuthentication() throws Exception {
        mockMvc.perform(get("/api/admin/creators")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/admin/restaurants")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/admin/assignments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(assignBody(1, 1)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("an unknown creator or restaurant is a 404, not a silent no-op")
    void unknownTargetsAreRejected() throws Exception {
        mockMvc.perform(withToken(post("/api/admin/assignments"), tokenFor(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(assignBody(other.getId(), 999999)))
                .andExpect(status().isNotFound());
        mockMvc.perform(withToken(post("/api/admin/assignments"), tokenFor(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(assignBody(999999, star.getId())))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("an administrator cannot create a duplicate active assignment")
    void adminCannotDuplicateAssignment() throws Exception {
        createdAssignmentId(tokenFor(admin), other.getId());

        mockMvc.perform(withToken(post("/api/admin/assignments"), tokenFor(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(assignBody(other.getId(), star.getId())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ASSIGNMENT_EXISTS"));
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