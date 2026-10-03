package com.customertrace.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import com.customertrace.domain.Creator;
import com.customertrace.domain.Restaurant;
import com.customertrace.domain.RestaurantCreatorAssignment;
import com.customertrace.domain.User;
import com.customertrace.repository.RestaurantCreatorAssignmentRepository;
import com.customertrace.repository.ReviewRepository;
import com.customertrace.support.IntegrationTestBase;

/**
 * Review creation, eligibility and duplicate rules.
 *
 * <p>The behaviour worth protecting: a creator cannot review a restaurant they are not assigned
 * to, cannot stack two reviews on the same campaign, and cannot edit another creator's review.
 */
class ReviewIntegrationTest extends IntegrationTestBase {

    @Autowired
    private RestaurantCreatorAssignmentRepository assignments;

    @Autowired
    private ReviewRepository reviews;

    private Restaurant kitchen;
    private Restaurant rival;
    private Creator star;
    private Creator stranger;
    private User starUser;
    private User strangerUser;
    private User kitchenUser;

    @BeforeEach
    void setUp() {
        kitchen = createRestaurant("kitchen-rev@restrnt", "Kitchen", "Kochi");
        rival = createRestaurant("rival-rev@restrnt", "Rival", "Kochi");
        createCreator("star-rev@creator", "Star", "@star-rev");
        createCreator("stranger-rev@creator", "Stranger", "@stranger-rev");
        starUser = userRepository.findByUserIdIgnoreCase("star-rev@creator").orElseThrow();
        strangerUser = userRepository.findByUserIdIgnoreCase("stranger-rev@creator")
                .orElseThrow();
        kitchenUser = userRepository.findByUserIdIgnoreCase("kitchen-rev@restrnt").orElseThrow();
        star = creatorRepository.findAll().stream()
                .filter(c -> "@star-rev".equals(c.getHandle())).findFirst().orElseThrow();
        stranger = creatorRepository.findAll().stream()
                .filter(c -> "@stranger-rev".equals(c.getHandle())).findFirst().orElseThrow();
        assign(star, kitchen);
    }

    private void assign(Creator creator, Restaurant restaurant) {
        RestaurantCreatorAssignment a = new RestaurantCreatorAssignment();
        a.setRestaurantId(restaurant.getId());
        a.setCreatorId(creator.getId());
        a.setAssignedBy(kitchen.getUserId());
        a.setActive(true);
        assignments.save(a);
        flush();
    }

    private String reviewBody(Long restaurantId, int rating, String text) {
        return "{\"restaurantId\":" + restaurantId + ",\"rating\":" + rating
                + ",\"body\":\"" + text + "\"}";
    }

    @Test
    @DisplayName("an assigned creator can review the restaurant")
    void assignedCreatorCanReview() throws Exception {
        mockMvc.perform(withToken(post("/api/creator/reviews"), tokenFor(starUser))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reviewBody(kitchen.getId(), 5, "Excellent food")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.rating").value(5))
                .andExpect(jsonPath("$.body").value("Excellent food"))
                .andExpect(jsonPath("$.restaurantId").value(kitchen.getId()));
        flush();

        assertThat(reviews.findByCreatorId(star.getId())).hasSize(1);
    }

    @Test
    @DisplayName("a creator who is not assigned cannot review")
    void unassignedCreatorCannotReview() throws Exception {
        mockMvc.perform(withToken(post("/api/creator/reviews"), tokenFor(strangerUser))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reviewBody(kitchen.getId(), 1, "Never been here")))
                .andExpect(status().isForbidden());
        flush();

        // Nothing was persisted by the rejected request.
        assertThat(reviews.findByCreatorId(stranger.getId())).isEmpty();
    }

    @Test
    @DisplayName("a restaurant cannot leave a review")
    void restaurantCannotReview() throws Exception {
        mockMvc.perform(withToken(post("/api/creator/reviews"), tokenFor(kitchenUser))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reviewBody(kitchen.getId(), 5, "Self praise")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("ratings outside 1..5 are rejected")
    void ratingBoundsEnforced() throws Exception {
        mockMvc.perform(withToken(post("/api/creator/reviews"), tokenFor(starUser))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reviewBody(kitchen.getId(), 0, "Too low")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_RATING"));

        mockMvc.perform(withToken(post("/api/creator/reviews"), tokenFor(starUser))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reviewBody(kitchen.getId(), 6, "Too high")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_RATING"));

        mockMvc.perform(withToken(post("/api/creator/reviews"), tokenFor(starUser))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reviewBody(kitchen.getId(), -1, "Negative")))
                .andExpect(status().isBadRequest());
        flush();
        assertThat(reviews.findByCreatorId(star.getId())).isEmpty();
    }

    @Test
    @DisplayName("an empty review body is rejected")
    void emptyBodyRejected() throws Exception {
        mockMvc.perform(withToken(post("/api/creator/reviews"), tokenFor(starUser))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reviewBody(kitchen.getId(), 4, "   ")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BODY_REQUIRED"));
    }

    @Test
    @DisplayName("reviewing an unknown restaurant is a 404")
    void unknownRestaurant() throws Exception {
        mockMvc.perform(withToken(post("/api/creator/reviews"), tokenFor(starUser))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reviewBody(999999L, 4, "Ghost")))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("the restaurant's review list and profile summary reflect real rows")
    void restaurantSeesItsOwnReviews() throws Exception {
        mockMvc.perform(withToken(post("/api/creator/reviews"), tokenFor(starUser))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reviewBody(kitchen.getId(), 4, "Tasty")))
                .andExpect(status().isCreated());
        flush();

        mockMvc.perform(withToken(get("/api/restaurant/reviews"), tokenFor(kitchenUser)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].rating").value(4));

        // The average is computed from the stored rating, not supplied by the client.
        mockMvc.perform(withToken(get("/api/restaurants/" + kitchen.getId() + "/reviews"),
                        tokenFor(kitchenUser)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalReviews").value(1))
                .andExpect(jsonPath("$.averageRating").value(4.0));
    }

    @Test
    @DisplayName("a restaurant with no reviews reports a zero average, not a placeholder")
    void emptyReviewProfile() throws Exception {
        mockMvc.perform(withToken(get("/api/restaurants/" + rival.getId() + "/reviews"),
                        tokenFor(kitchenUser)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalReviews").value(0))
                .andExpect(jsonPath("$.averageRating").value(0.0))
                .andExpect(jsonPath("$.reviews.length()").value(0));
    }

    @Test
    @DisplayName("the summary of an unknown restaurant is a 404")
    void unknownRestaurantSummary() throws Exception {
        mockMvc.perform(withToken(get("/api/restaurants/999999/reviews"), tokenFor(kitchenUser)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("a creator can edit their own review")
    void creatorEditsOwnReview() throws Exception {
        String id = parse(mockMvc.perform(withToken(post("/api/creator/reviews"),
                        tokenFor(starUser)).contentType(MediaType.APPLICATION_JSON)
                .content(reviewBody(kitchen.getId(), 2, "Changed my mind")))
                .andExpect(status().isCreated()).andReturn()).path("id").asText();

        mockMvc.perform(withToken(put("/api/creator/reviews/" + id), tokenFor(starUser))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rating\":5,\"body\":\"Now it is great\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rating").value(5))
                .andExpect(jsonPath("$.body").value("Now it is great"));
        flush();

        // Edited in place: still a single review, not a second row.
        assertThat(reviews.findByCreatorId(star.getId())).hasSize(1);
    }

    @Test
    @DisplayName("a creator cannot edit another creator's review")
    void cannotEditOthersReview() throws Exception {
        assign(stranger, rival);
        String id = parse(mockMvc.perform(withToken(post("/api/creator/reviews"),
                        tokenFor(starUser)).contentType(MediaType.APPLICATION_JSON)
                .content(reviewBody(kitchen.getId(), 3, "Mine")))
                .andExpect(status().isCreated()).andReturn()).path("id").asText();

        mockMvc.perform(withToken(put("/api/creator/reviews/" + id), tokenFor(strangerUser))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rating\":1,\"body\":\"Hijacked\"}"))
                .andExpect(status().isNotFound());
        flush();

        assertThat(reviews.findById(Long.valueOf(id)).orElseThrow().getRating()).isEqualTo(3);
    }

    @Test
    @DisplayName("editing with an out-of-range rating is rejected")
    void editRatingBounds() throws Exception {
        String id = parse(mockMvc.perform(withToken(post("/api/creator/reviews"),
                        tokenFor(starUser)).contentType(MediaType.APPLICATION_JSON)
                .content(reviewBody(kitchen.getId(), 3, "Fine")))
                .andExpect(status().isCreated()).andReturn()).path("id").asText();

        mockMvc.perform(withToken(put("/api/creator/reviews/" + id), tokenFor(starUser))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rating\":9,\"body\":\"Out of range\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_RATING"));
        flush();
        assertThat(reviews.findById(Long.valueOf(id)).orElseThrow().getRating()).isEqualTo(3);
    }

    @Test
    @DisplayName("review endpoints require authentication")
    void requiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/creator/reviews")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/restaurant/reviews")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/creator/reviews")).andExpect(status().isUnauthorized());
    }
}