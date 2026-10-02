package com.customertrace.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import com.customertrace.domain.Creator;
import com.customertrace.domain.Restaurant;
import com.customertrace.domain.User;
import com.customertrace.support.IntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * The dual-approval campaign workflow, exercised end to end over HTTP.
 *
 * <p>The central rule: a campaign becomes {@code ACTIVE} only when the TryNearby administrator
 * <em>and</em> the restaurant have both approved, and either rejection blocks it.
 */
class CampaignWorkflowIntegrationTest extends IntegrationTestBase {

    private User admin;
    private User restaurantUser;
    private User otherRestaurantUser;
    private User creatorUser;
    private Restaurant restaurant;
    private Creator creator;

    @BeforeEach
    void setUpWorld() {
        admin = createUser("boss@Trynearby", "ADMIN", "Boss");
        restaurant = createRestaurant("chef@restrnt", "Copper Kitchen", "Kochi");
        createRestaurant("other@restrnt", "Other Kitchen", "Chennai");
        restaurantUser = userRepository.findByUserIdIgnoreCase("chef@restrnt").orElseThrow();
        otherRestaurantUser = userRepository.findByUserIdIgnoreCase("other@restrnt").orElseThrow();
        creator = createCreator("star@creator", "Star", "@star");
        creatorUser = userRepository.findByUserIdIgnoreCase("star@creator").orElseThrow();
    }

    private String campaignJson(Long restaurantId) {
        // A window that is open today, so the campaign can actually be redeemed later.
        LocalDate start = LocalDate.now().minusDays(1);
        LocalDate end = LocalDate.now().plusDays(30);
        return """
                {"restaurantId":%d,"title":"Weekend Special","offerText":"Flat 15%% Off",
                 "description":"All day","discountPercent":15,"targetCustomers":50,
                 "durationDays":7,"startDate":"%s","endDate":"%s"}
                """.formatted(restaurantId, start, end);
    }

    /** Creates a campaign, submits it, and returns its id. */
    private long createAndSubmitCampaign() throws Exception {
        MvcResult created = mockMvc.perform(withToken(post("/api/creator/campaigns"),
                        tokenFor(creatorUser))
                .contentType(MediaType.APPLICATION_JSON)
                .content(campaignJson(restaurant.getId())))
                .andExpect(status().isCreated())
                .andReturn();
        long id = parse(created).path("id").asLong();

        mockMvc.perform(withToken(post("/api/creator/campaigns/" + id + "/submit"),
                        tokenFor(creatorUser)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"));
        return id;
    }

    private void adminApprove(long id) throws Exception {
        mockMvc.perform(withToken(post("/api/admin/campaigns/" + id + "/approve"), tokenFor(admin))
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());
    }

    private void restaurantApprove(long id, User actor) throws Exception {
        mockMvc.perform(withToken(post("/api/restaurants/campaigns/" + id + "/approve"),
                        tokenFor(actor))
                .contentType(MediaType.APPLICATION_JSON).content("{}"));
    }

@Test
    @DisplayName("a campaign starts as a draft owned by the creator")
    void createsDraft() throws Exception {
        JsonNode body = parse(mockMvc.perform(withToken(post("/api/creator/campaigns"),
                        tokenFor(creatorUser))
                .contentType(MediaType.APPLICATION_JSON)
                .content(campaignJson(restaurant.getId())))
                .andExpect(status().isCreated())
                .andReturn());

        assertThat(body.path("status").asText()).isEqualTo("DRAFT");
        assertThat(body.path("creatorId").asLong()).isEqualTo(creator.getId());
        // The creator cannot dictate their own approval state.
        assertThat(body.path("adminDecision").isNull()
                || body.path("adminDecision").asText().isEmpty()).isTrue();
    }

    @Test
    @DisplayName("admin approval alone leaves the campaign pending")
    void adminAloneDoesNotActivate() throws Exception {
        long id = createAndSubmitCampaign();

        JsonNode body = parse(mockMvc.perform(withToken(
                        post("/api/admin/campaigns/" + id + "/approve"), tokenFor(admin))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
                .andExpect(status().isOk())
                .andReturn());

        assertThat(body.path("status").asText()).isEqualTo("PENDING");
        assertThat(body.path("adminDecision").asText()).isEqualTo("APPROVED");
        assertThat(body.path("restaurantDecision").isNull()
                || body.path("restaurantDecision").asText().isEmpty()).isTrue();
    }

    @Test
    @DisplayName("restaurant approval alone leaves the campaign pending")
    void restaurantAloneDoesNotActivate() throws Exception {
        long id = createAndSubmitCampaign();

        JsonNode body = parse(mockMvc.perform(withToken(
                        post("/api/restaurants/campaigns/" + id + "/approve"),
                        tokenFor(restaurantUser))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
                .andExpect(status().isOk())
                .andReturn());

        assertThat(body.path("status").asText()).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("both approvals activate the campaign and issue a promo code")
    void bothApprovalsActivate() throws Exception {
        long id = createAndSubmitCampaign();
        adminApprove(id);

        JsonNode body = parse(mockMvc.perform(withToken(
                        post("/api/restaurants/campaigns/" + id + "/approve"),
                        tokenFor(restaurantUser))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
                .andExpect(status().isOk())
                .andReturn());

        assertThat(body.path("status").asText()).isEqualTo("ACTIVE");
        assertThat(body.path("activatedAt").isNull()).isFalse();

        // Issued on submission; NN=50 customers, DD=07 days, tier A-J.
        assertThat(body.path("promoCode").asText()).matches("^[A-Z]{2}5007[A-J]$");
    }

    @Test
    @DisplayName("an admin rejection blocks activation even after the restaurant approved")
    void rejectionBlocksActivation() throws Exception {
        long id = createAndSubmitCampaign();
        restaurantApprove(id, restaurantUser);

        JsonNode body = parse(mockMvc.perform(withToken(
                        post("/api/admin/campaigns/" + id + "/reject"), tokenFor(admin))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"Not on brand\"}"))
                .andExpect(status().isOk())
                .andReturn());

        assertThat(body.path("status").asText()).isEqualTo("REJECTED");
        assertThat(body.path("adminReason").asText()).isEqualTo("Not on brand");
    }

    @Test
    @DisplayName("a rejected campaign cannot then be approved")
    void rejectedCannotBeApproved() throws Exception {
        long id = createAndSubmitCampaign();
        mockMvc.perform(withToken(post("/api/admin/campaigns/" + id + "/reject"), tokenFor(admin))
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"No\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(withToken(post("/api/restaurants/campaigns/" + id + "/approve"),
                        tokenFor(restaurantUser))
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_TRANSITION"));
    }

    @Test
    @DisplayName("another restaurant cannot approve a campaign")
    void crossRestaurantCannotApprove() throws Exception {
        long id = createAndSubmitCampaign();

        // Not-accessible (404) rather than forbidden, so existence is not confirmed.
        mockMvc.perform(withToken(post("/api/restaurants/campaigns/" + id + "/approve"),
                        tokenFor(otherRestaurantUser))
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("a restaurant cannot record the administrator decision")
    void restaurantCannotRecordAdminDecision() throws Exception {
        long id = createAndSubmitCampaign();

        mockMvc.perform(withToken(post("/api/admin/campaigns/" + id + "/approve"),
                        tokenFor(restaurantUser))
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("a duplicate decision is refused")
    void duplicateDecisionRefused() throws Exception {
        long id = createAndSubmitCampaign();
        adminApprove(id);

        // The campaign has left the pending state, so a second decision is invalid.
        mockMvc.perform(withToken(post("/api/admin/campaigns/" + id + "/approve"), tokenFor(admin))
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("another creator cannot read or submit a campaign")
    void crossCreatorCannotTouchCampaign() throws Exception {
        long id = createAndSubmitCampaign();
        createCreator("rival@creator", "Rival", "@rival");
        User otherUser = userRepository.findByUserIdIgnoreCase("rival@creator").orElseThrow();

        mockMvc.perform(withToken(get("/api/creator/campaigns/" + id), tokenFor(otherUser)))
                .andExpect(status().isNotFound());
        mockMvc.perform(withToken(post("/api/creator/campaigns/" + id + "/submit"),
                        tokenFor(otherUser)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("resubmission clears prior decisions and increments the counter")
    void resubmissionClearsDecisions() throws Exception {
        long id = createAndSubmitCampaign();
        mockMvc.perform(withToken(post("/api/admin/campaigns/" + id + "/reject"), tokenFor(admin))
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Fix the offer\"}"))
                .andExpect(status().isOk());

        JsonNode body = parse(mockMvc.perform(withToken(
                        post("/api/creator/campaigns/" + id + "/submit"), tokenFor(creatorUser)))
                .andExpect(status().isOk())
                .andReturn());

        assertThat(body.path("status").asText()).isEqualTo("PENDING");
        assertThat(body.path("resubmissionCount").asInt()).isEqualTo(1);
        // The stale rejection must not survive into the new submission.
        assertThat(body.path("adminDecision").isNull()
                || body.path("adminDecision").asText().isEmpty()).isTrue();
    }

    @Test
    @DisplayName("the status history records every transition with its reason")
    void recordsStatusHistory() throws Exception {
        long id = createAndSubmitCampaign();
        mockMvc.perform(withToken(post("/api/admin/campaigns/" + id + "/reject"), tokenFor(admin))
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Off brand\"}"))
                .andExpect(status().isOk());

        JsonNode history = parse(mockMvc.perform(withToken(
                        get("/api/admin/campaigns/" + id + "/history"), tokenFor(admin)))
                .andExpect(status().isOk())
                .andReturn());

        String rendered = history.toString();
        assertThat(rendered).contains("DRAFT").contains("PENDING").contains("REJECTED");
        assertThat(rendered).contains("Off brand");
    }

    @Test
    @DisplayName("an unencodable customer target is rejected at creation")
    void rejectsUnencodableTarget() throws Exception {
        // 150 cannot be encoded as NN, so it must be refused rather than truncated to 15.
        String bad = campaignJson(restaurant.getId())
                .replace("\"targetCustomers\":50", "\"targetCustomers\":150");

        mockMvc.perform(withToken(post("/api/creator/campaigns"), tokenFor(creatorUser))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bad))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("a malformed create payload is rejected with field errors")
    void validatesCreatePayload() throws Exception {
        mockMvc.perform(withToken(post("/api/creator/campaigns"), tokenFor(creatorUser))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"restaurantId\":1,\"title\":\"\",\"discountPercent\":150}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors").exists());
    }
}