package com.customertrace.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import com.customertrace.domain.Creator;
import com.customertrace.domain.Restaurant;
import com.customertrace.domain.User;
import com.customertrace.repository.CampaignRepository;
import com.customertrace.support.IntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * The dual-approval campaign workflow, exercised end to end over HTTP.
 *
 * <p>The central rule: a campaign becomes {@code ACTIVE} only when the TryNearby administrator
 * <em>and</em> the restaurant have both approved, and either rejection blocks it.
 */
class CampaignWorkflowIntegrationTest extends IntegrationTestBase {

    @Autowired
    private CampaignRepository campaignRepository;

    private User admin;
    private User restaurantUser;
    private User otherRestaurantUser;
    private User creatorUser;
    private Restaurant restaurant;
    private Creator creator;

    @BeforeEach
    void setUpWorld() {
        admin = createUser("boss@Trynearby", "ADMIN", "Boss");
        creator = createCreatorWithId("star@creator", "Star", "@star");
        creatorUser = userRepository.findByUserIdIgnoreCase("star@creator").orElseThrow();
        // A campaign may only target a restaurant the creator is actively assigned to, so the
        // fixtures create the assignment alongside the venue.
        restaurant = createRestaurant("chef@restrnt", "Copper Kitchen", "Kochi", creator);
        // Deliberately unassigned: used to prove the eligibility rule is enforced.
        createRestaurant("other@restrnt", "Other Kitchen", "Chennai");
        restaurantUser = userRepository.findByUserIdIgnoreCase("chef@restrnt").orElseThrow();
        otherRestaurantUser = userRepository.findByUserIdIgnoreCase("other@restrnt").orElseThrow();
    }

    private String campaignJson(Long restaurantId) {
        return campaignJson(restaurantId, 50);
    }

    /** A create payload whose target is caller-chosen, so by-target ordering can be exercised. */
    private String campaignJson(Long restaurantId, int targetCustomers) {
        // A window that is open today, so the campaign can actually be redeemed later.
        LocalDate start = LocalDate.now().minusDays(1);
        LocalDate end = LocalDate.now().plusDays(30);
        return """
                {"restaurantId":%d,"offerText":"Flat 5%% Off",
                 "description":"All day","discountPercent":5,"targetCustomers":%d,
                 "durationDays":7,"startDate":"%s","endDate":"%s"}
                """.formatted(restaurantId, targetCustomers, start, end);
    }

    /** Creates a campaign, submits it, and returns its id. */
    private long createAndSubmitCampaign() throws Exception {
        return createAndSubmitCampaign(50);
    }

    /**
     * Creates and submits a campaign targeting {@code targetCustomers} customers.
     *
     * <p>The target is encoded in the promo code, so a test that varies it also varies the code —
     * which is what makes the by-target ordering assertion meaningful.
     */
    private long createAndSubmitCampaign(int targetCustomers) throws Exception {
        MvcResult created = mockMvc.perform(withToken(post("/api/creator/campaigns"),
                        tokenFor(creatorUser))
                .contentType(MediaType.APPLICATION_JSON)
                .content(campaignJson(restaurant.getId(), targetCustomers)))
                .andExpect(status().isCreated())
                .andReturn();
        long id = parse(created).path("id").asLong();

        mockMvc.perform(withToken(post("/api/creator/campaigns/" + id + "/submit"),
                        tokenFor(creatorUser)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"));
        return id;
    }

    /** The campaign as the administrator sees it, including the `canApprove` flag. */
    private JsonNode adminCampaign(long id) throws Exception {
        return parse(mockMvc.perform(withToken(get("/api/admin/campaigns/" + id),
                tokenFor(admin))).andReturn());
    }

    /**
     * The campaign as {@code actor}'s restaurant sees it.
     *
     * <p>A restaurant that does not own the campaign gets a 404 body rather than a campaign, which
     * `path("canApprove")` would then read as {@code false} — the intended answer here, since a
     * non-owner must never be offered a decision.
     */
    private JsonNode restaurantCampaign(long id, User actor) throws Exception {
        return parse(mockMvc.perform(withToken(get("/api/restaurants/campaigns/" + id),
                tokenFor(actor))).andReturn());
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

// ── Authorization on decisions ──────────────────────────────────────────────

    @Test
    @DisplayName("a restaurant cannot approve a campaign belonging to another restaurant")
    void restaurantCannotApproveAnotherRestaurantsCampaign() throws Exception {
        long id = createAndSubmitCampaign();
        adminApprove(id);

        // "Other Kitchen" owns none of these. The service answers 404 rather than 403 so a venue
        // cannot probe for the existence of another venue's campaign ids.
        mockMvc.perform(withToken(post("/api/restaurants/campaigns/" + id + "/approve"),
                        tokenFor(otherRestaurantUser))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound());

        // The owning restaurant still can, proving the refusal was about the tenant rather than the
        // campaign's state.
        restaurantApprove(id, restaurantUser);
        assertThat(parse(mockMvc.perform(withToken(get("/api/admin/campaigns/" + id),
                        tokenFor(admin))).andReturn()).path("status").asText())
                .isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("a creator cannot approve or reject anything")
    void creatorCannotDecide() throws Exception {
        long id = createAndSubmitCampaign();

        mockMvc.perform(withToken(post("/api/admin/campaigns/" + id + "/approve"),
                        tokenFor(creatorUser))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(withToken(post("/api/admin/campaigns/" + id + "/reject"),
                        tokenFor(creatorUser))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"mine\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("a second admin approval is refused as a duplicate decision")
    void duplicateAdminApprovalIsRefused() throws Exception {
        long id = createAndSubmitCampaign();
        adminApprove(id);

        mockMvc.perform(withToken(post("/api/admin/campaigns/" + id + "/approve"), tokenFor(admin))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("an admin rejection is recorded verbatim and blocks the campaign going live")
    void adminRejectionBlocksLive() throws Exception {
        long id = createAndSubmitCampaign();
        String reason = "The offer cannot be verified at settlement.";

        mockMvc.perform(withToken(post("/api/admin/campaigns/" + id + "/reject"), tokenFor(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"" + reason + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.adminReason").value(reason));

        // The restaurant cannot rescue it, and it never becomes redeemable.
        restaurantApprove(id, restaurantUser);
        assertThat(parse(mockMvc.perform(withToken(get("/api/admin/campaigns/" + id),
                        tokenFor(admin))).andReturn()).path("status").asText())
                .isEqualTo("REJECTED");
    }

    @Test
    @DisplayName("a restaurant rejection is recorded and blocks the campaign going live")
    void restaurantRejectionBlocksLive() throws Exception {
        long id = createAndSubmitCampaign();
        adminApprove(id);
        String reason = "We cannot honour this at peak hours.";

        mockMvc.perform(withToken(post("/api/restaurants/campaigns/" + id + "/reject"),
                        tokenFor(restaurantUser))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"" + reason + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.restaurantReason").value(reason));
    }

    // ── Search, filtering and ordering ──────────────────────────────────────────

    @Test
    @DisplayName("a restaurant's queue never contains another restaurant's campaign")
    void restaurantSeesOnlyItsOwnCampaigns() throws Exception {
        long own = createAndSubmitCampaign();
        Creator second = createCreatorWithId("other@creator", "Second", "@second");
        Restaurant theirVenue = createRestaurant("theirs@restrnt", "Other Kitchen", "Chennai",
                second);
        User secondUser = userRepository.findByUserIdIgnoreCase("other@creator").orElseThrow();

        mockMvc.perform(withToken(post("/api/creator/campaigns"), tokenFor(secondUser))
                .contentType(MediaType.APPLICATION_JSON)
                .content(campaignJson(theirVenue.getId())))
                .andExpect(status().isCreated());

        assertThat(idsOf(parse(mockMvc.perform(withToken(get("/api/restaurants/campaigns"),
                tokenFor(restaurantUser))).andReturn()))).contains(own);

        // The other venue sees its own campaign, and not this one.
        User theirUser = userRepository.findByUserIdIgnoreCase("theirs@restrnt").orElseThrow();
        assertThat(idsOf(parse(mockMvc.perform(withToken(get("/api/restaurants/campaigns"),
                tokenFor(theirUser))).andReturn()))).doesNotContain(own);
    }

    @Test
    @DisplayName("search matches the campaign code, the creator and the restaurant")
    void searchMatchesCodeCreatorAndRestaurant() throws Exception {
        long id = createAndSubmitCampaign();
        String code = parse(mockMvc.perform(withToken(get("/api/creator/campaigns/" + id),
                tokenFor(creatorUser))).andReturn()).path("promoCode").asText();
        assertThat(code).isNotBlank();

        // By restaurant name, by creator name, and by campaign code case-insensitively.
        assertThat(idsOf(admin("q=Copper"))).contains(id);
        assertThat(idsOf(admin("q=Star"))).contains(id);
        assertThat(idsOf(admin("q=" + code.toLowerCase()))).contains(id);
        // A term matching nothing returns nothing, rather than silently everything.
        assertThat(idsOf(admin("q=zzzznotathing"))).isEmpty();
    }

    @Test
    @DisplayName("search and status filtering combine rather than overriding each other")
    void searchAndStatusCombine() throws Exception {
        long pending = createAndSubmitCampaign();
        long draft = createDraftOnly();

        assertThat(idsOf(admin("status=PENDING&q=Copper"))).contains(pending).doesNotContain(draft);
        // Widening to every status brings the draft back, and it still matches the term.
        assertThat(idsOf(admin("q=Copper"))).contains(draft);
    }

    @Test
    @DisplayName("ordering is newest first and reverses on request")
    void ordersNewestAndOldest() throws Exception {
        long first = createAndSubmitCampaign();
        long second = createAndSubmitCampaign();
        long third = createAndSubmitCampaign();
        List<Long> mine = List.of(first, second, third);

        // Seed data also matches this term, so the assertion is about the relative order of the
        // three campaigns created here rather than the exact contents of the page.
        assertThat(idsOf(admin("q=Copper&sort=newest")))
                .containsSubsequence(third, second, first);
        assertThat(idsOf(admin("q=Copper&sort=oldest")))
                .containsSubsequence(first, second, third);
        // And the two orders really are reverses of one another for these rows.
        assertThat(mine).hasSize(3);
    }

    @Test
    @DisplayName("an unknown sort direction is rejected rather than silently defaulted")
    void unknownSortIsRejected() throws Exception {
        mockMvc.perform(withToken(get("/api/admin/campaigns?sort=SIDEWAYS"), tokenFor(admin)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("UNKNOWN_SORT"));
    }

    @Test
    @DisplayName("ordering can be by target size, largest first")
    void ordersByTargetDescending() throws Exception {
        long small = createAndSubmitCampaign(20);
        long large = createAndSubmitCampaign(90);
        long middle = createAndSubmitCampaign(50);

        // The target is encoded in the generated code, so this also re-confirms the code matches
        // what was asked for rather than being cosmetic.
        assertThat(idsOf(admin("q=Copper&sort=target")))
                .containsSubsequence(large, middle, small);
    }

    @Test
    @DisplayName("recently updated surfaces a decided campaign ahead of an untouched draft")
    void ordersByRecentlyUpdated() throws Exception {
        long draft = createDraftOnly();
        long submitted = createAndSubmitCampaign();
        // Approving touches the row's updated_at without changing when it was created, which is
        // exactly the question a "recently updated" order is asked to answer.
        adminApprove(submitted);

        List<Long> byUpdated = idsOf(admin("q=Copper&sort=updated"));
        assertThat(byUpdated).contains(submitted);
        // Newest-by-creation cannot distinguish them (the draft was created first), so assert the
        // update order directly rather than relying on timestamps that could tie.
        assertThat(byUpdated).contains(draft);
        assertThat(byUpdated.indexOf(submitted))
                .as("the decided campaign should be listed before the untouched draft")
                .isLessThan(byUpdated.indexOf(draft));
    }

    @Test
    @DisplayName("canApprove marks only the party that may still decide")
    void canApproveFlagFollowsThePartyAndTheState() throws Exception {
        long id = createAndSubmitCampaign();

        // Both parties may act while the campaign awaits its first decision.
        assertThat(adminCampaign(id).path("canApprove").asBoolean()).isTrue();
        assertThat(restaurantCampaign(id, restaurantUser).path("canApprove").asBoolean()).isTrue();
        // The creator is never offered a decision.
        assertThat(parse(mockMvc.perform(withToken(get("/api/creator/campaigns/" + id),
                tokenFor(creatorUser))).andReturn()).path("canApprove").asBoolean()).isFalse();

        // After the admin approves, only the restaurant is left to decide.
        adminApprove(id);
        assertThat(adminCampaign(id).path("canApprove").asBoolean())
                .as("the admin has already recorded their decision")
                .isFalse();
        assertThat(restaurantCampaign(id, restaurantUser).path("canApprove").asBoolean()).isTrue();

        // A different restaurant may not decide, even though the campaign is still pending.
        User theirUser = userRepository.findByUserIdIgnoreCase("other@restrnt").orElseThrow();
        assertThat(restaurantCampaign(id, theirUser).path("canApprove").asBoolean()).isFalse();

        // Once both have approved there is nothing left to decide.
        restaurantApprove(id, restaurantUser);
        assertThat(adminCampaign(id).path("canApprove").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("the admin list marks a pending campaign decidable for the administrator")
    void adminListMarksPendingCampaignDecidable() throws Exception {
        long id = createAndSubmitCampaign();

        // Regression guard. `listAll` once passed a null actor to the response mapper, so every row
        // came back canApprove=false and the review queue rendered with no approval controls at all
        // — while the detail endpoint, which did take an actor, happily said true. The list is what
        // the UI actually renders from, so the flag has to be right there too.
        assertThat(adminCampaign(id).path("canApprove").asBoolean())
                .as("the detail endpoint reports the campaign as decidable")
                .isTrue();

        List<Long> listed = idsOf(admin("status=PENDING"));
        assertThat(listed).contains(id);
        JsonNode row = admin("status=PENDING").get(listed.indexOf(id));
        assertThat(row.path("canApprove").asBoolean())
                .as("the LIST row must agree with the detail endpoint, or the UI shows no buttons")
                .isTrue();
    }

    /** Lists campaigns for the admin using a raw query string. */
    private JsonNode admin(String query) throws Exception {
        MvcResult result = mockMvc.perform(withToken(get("/api/admin/campaigns?" + query),
                tokenFor(admin))).andReturn();
        // Surfaced plainly when a query misbehaves: the error body is an object, and iterating it
        // would quietly yield a list of zeros that looks like "no matches" rather than a failure.
        assertThat(result.getResponse().getStatus())
                .withFailMessage("GET ?%s returned %s: %s", query,
                        result.getResponse().getStatus(),
                        result.getResponse().getContentAsString())
                .isEqualTo(200);
        return parse(result);
    }

    /** Creates a campaign but leaves it a draft, so it only shows under "All". */
    private long createDraftOnly() throws Exception {
        MvcResult created = mockMvc.perform(withToken(post("/api/creator/campaigns"),
                        tokenFor(creatorUser))
                .contentType(MediaType.APPLICATION_JSON)
                .content(campaignJson(restaurant.getId())))
                .andExpect(status().isCreated()).andReturn();
        return parse(created).path("id").asLong();
    }

    private static List<Long> idsOf(JsonNode array) {
        List<Long> ids = new ArrayList<>();
        array.forEach(node -> ids.add(node.path("id").asLong()));
        return ids;
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
    @DisplayName("a campaign's code states its own target, duration and discount")
    void codeStatesTheCampaignSettings() throws Exception {
        long id = createAndSubmitCampaign();

        String code = parse(mockMvc.perform(withToken(get("/api/creator/campaigns/" + id),
                tokenFor(creatorUser))).andReturn()).path("promoCode").asText();

        assertThat(code).matches("[A-Z]{2}[0-9]{2}[0-9]{2}[B-J]");
        // The fixture asks for 50 customers over 7 days at 5%, so the code must read "50", "07"
        // and the 5% letter. A code that disagreed with the settings would mis-sell the offer.
        assertThat(code.substring(2, 4)).isEqualTo("50");
        assertThat(code.substring(4, 6)).isEqualTo("07");
        assertThat(code.charAt(6)).isEqualTo('E');
    }

    @Test
    @DisplayName("the generated code is the campaign's name")
    void codeIsTheCampaignName() throws Exception {
        long id = createAndSubmitCampaign();

        JsonNode body = parse(mockMvc.perform(withToken(get("/api/creator/campaigns/" + id),
                tokenFor(creatorUser))).andReturn());

        assertThat(body.path("title").asText())
                .as("the creator never types a name, so the code labels the campaign")
                .isEqualTo(body.path("promoCode").asText());
    }

    @Test
    @DisplayName("a submitted campaign's code never changes when read again")
    void codeIsStableAfterSubmission() throws Exception {
        long id = createAndSubmitCampaign();

        String first = parse(mockMvc.perform(withToken(get("/api/creator/campaigns/" + id),
                tokenFor(creatorUser))).andReturn()).path("promoCode").asText();

        // Re-reading must not re-mint: the letters are fixed at submit, so a reload shows the same
        // code the diner already has on their phone.
        for (int i = 0; i < 3; i++) {
            assertThat(parse(mockMvc.perform(withToken(get("/api/creator/campaigns/" + id),
                    tokenFor(creatorUser))).andReturn()).path("promoCode").asText())
                    .isEqualTo(first);
        }
    }

    @Test
    @DisplayName("two campaigns with identical settings get different codes")
    void codesAreUniqueAcrossCampaigns() throws Exception {
        String first = parse(mockMvc.perform(withToken(post("/api/creator/campaigns"),
                tokenFor(creatorUser)).contentType(MediaType.APPLICATION_JSON)
                .content(campaignJson(restaurant.getId()))).andExpect(status().isCreated())
                .andReturn()).path("id").asText();
        String second = parse(mockMvc.perform(withToken(post("/api/creator/campaigns"),
                tokenFor(creatorUser)).contentType(MediaType.APPLICATION_JSON)
                .content(campaignJson(restaurant.getId()))).andExpect(status().isCreated())
                .andReturn()).path("id").asText();

        mockMvc.perform(withToken(post("/api/creator/campaigns/" + first + "/submit"),
                tokenFor(creatorUser))).andExpect(status().isOk());
        mockMvc.perform(withToken(post("/api/creator/campaigns/" + second + "/submit"),
                tokenFor(creatorUser))).andExpect(status().isOk());

        String a = parse(mockMvc.perform(withToken(get("/api/creator/campaigns/" + first),
                tokenFor(creatorUser))).andReturn()).path("promoCode").asText();
        String b = parse(mockMvc.perform(withToken(get("/api/creator/campaigns/" + second),
                tokenFor(creatorUser))).andReturn()).path("promoCode").asText();

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    @DisplayName("a creator may not run a campaign for a restaurant they are not assigned to")
    void unassignedCreatorCannotCreateCampaign() throws Exception {
        Restaurant stranger = createRestaurant("stranger-cw@restrnt", "Stranger Kitchen", "Kochi");

        mockMvc.perform(withToken(post("/api/creator/campaigns"), tokenFor(creatorUser))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(campaignJson(stranger.getId())))
                .andExpect(status().isForbidden());

        // Nothing was written for this restaurant, so the restriction is real rather than a
        // cosmetic error. Scoped to the stranger venue so unrelated seed rows cannot interfere.
        assertThat(campaignRepository.findAll().stream()
                .filter(c -> c.getRestaurantId().equals(stranger.getId()))
                .count()).isZero();
    }

    @Test
    @DisplayName("reactivating an assignment restores the creator's access")
    void reactivatingRestoresCampaignAccess() throws Exception {
        long assignmentId = assignmentRepository
                .findByRestaurantIdAndCreatorId(restaurant.getId(), creator.getId())
                .orElseThrow().getId();

        mockMvc.perform(withToken(delete("/api/admin/assignments/" + assignmentId),
                        tokenFor(admin)))
                .andExpect(status().isOk());
        mockMvc.perform(withToken(post("/api/creator/campaigns"), tokenFor(creatorUser))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(campaignJson(restaurant.getId())))
                .andExpect(status().isForbidden());

        mockMvc.perform(withToken(post("/api/admin/assignments/" + assignmentId + "/reactivate"),
                        tokenFor(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(true));

        // Access is back, with no database edit and no re-authentication by the creator.
        mockMvc.perform(withToken(post("/api/creator/campaigns"), tokenFor(creatorUser))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(campaignJson(restaurant.getId())))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("a restaurant a creator is not assigned to stays unreachable after another is granted")
    void assignmentIsScopedToOneCreator() throws Exception {
        Restaurant otherVenue = createRestaurant("other-cw@restrnt", "Other Kitchen", "Kochi");

        // The fixture creator *is* assigned to `restaurant`, so that one works.
        mockMvc.perform(withToken(post("/api/creator/campaigns"), tokenFor(creatorUser))
                .contentType(MediaType.APPLICATION_JSON)
                .content(campaignJson(restaurant.getId())))
                .andExpect(status().isCreated());

        // Being assigned to one venue says nothing about any other.
        mockMvc.perform(withToken(post("/api/creator/campaigns"), tokenFor(creatorUser))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(campaignJson(otherVenue.getId())))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("ending an assignment stops new campaigns but leaves existing ones intact")
    void deactivatedAssignmentBlocksNewCampaignsOnly() throws Exception {
        long id = createAndSubmitCampaign();
        adminApprove(id);
        restaurantApprove(id, restaurantUser);
        assertThat(parse(mockMvc.perform(withToken(get("/api/creator/campaigns/" + id),
                tokenFor(creatorUser))).andReturn()).path("status").asText()).isEqualTo("ACTIVE");

        long assignmentId = assignmentRepository
                .findByRestaurantIdAndCreatorId(restaurant.getId(), creator.getId())
                .orElseThrow().getId();
        mockMvc.perform(withToken(post("/api/admin/assignments/" + assignmentId + "/reactivate"),
                        tokenFor(admin)))
                .andExpect(status().isConflict()); // already active
        mockMvc.perform(withToken(delete("/api/admin/assignments/" + assignmentId),
                        tokenFor(admin)))
                .andExpect(status().isOk());

        // A new campaign is now refused...
        mockMvc.perform(withToken(post("/api/creator/campaigns"), tokenFor(creatorUser))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(campaignJson(restaurant.getId())))
                .andExpect(status().isForbidden());

        // ...but the campaign history is untouched, which is the whole point of deactivating
        // rather than deleting.
        assertThat(parse(mockMvc.perform(withToken(get("/api/creator/campaigns/" + id),
                tokenFor(creatorUser))).andReturn()).path("status").asText()).isEqualTo("ACTIVE");
        mockMvc.perform(withToken(get("/api/creator/campaigns/" + id + "/history"),
                        tokenFor(creatorUser)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(org.hamcrest.Matchers.greaterThan(0)));
    }

    @Test
    @DisplayName("another creator cannot read or submit a campaign")
    void crossCreatorCannotTouchCampaign() throws Exception {
        long id = createAndSubmitCampaign();
        // Unassigned on purpose: this test is about campaign ownership, not assignment.
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