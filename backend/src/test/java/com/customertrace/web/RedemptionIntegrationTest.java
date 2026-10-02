package com.customertrace.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import com.customertrace.domain.Restaurant;
import com.customertrace.domain.User;
import com.customertrace.support.IntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Promo-code lookup, scanning and verified redemption.
 *
 * <p>The rule under test: a scan is interest and never a customer; only an explicit redemption
 * counts, and the E–J monthly tier limit is enforced inside the transaction.
 */
class RedemptionIntegrationTest extends IntegrationTestBase {

    private User admin;
    private User restaurantUser;
    private User otherRestaurantUser;
    private User creatorUser;
    private Restaurant restaurant;
    private String promoCode;

    @BeforeEach
    void setUpWorld() throws Exception {
        admin = createUser("boss@Trynearby", "ADMIN", "Boss");
        restaurant = createRestaurant("chef@restrnt", "Copper Kitchen", "Kochi");
        createRestaurant("other@restrnt", "Other Kitchen", "Chennai");
        restaurantUser = userRepository.findByUserIdIgnoreCase("chef@restrnt").orElseThrow();
        otherRestaurantUser = userRepository.findByUserIdIgnoreCase("other@restrnt").orElseThrow();
        createCreator("star@creator", "Star", "@star");
        creatorUser = userRepository.findByUserIdIgnoreCase("star@creator").orElseThrow();

        // Build a live, fully approved campaign and remember its promo code.
        String campaign = """
                {"restaurantId":%d,"title":"Live Test","offerText":"15%% off",
                 "discountPercent":15,"targetCustomers":50,"durationDays":10,
                 "startDate":"%s","endDate":"%s"}
                """.formatted(restaurant.getId(), LocalDate.now().minusDays(1),
                LocalDate.now().plusDays(30));

        MvcResult created = mockMvc.perform(withToken(post("/api/creator/campaigns"),
                        tokenFor(creatorUser))
                .contentType(MediaType.APPLICATION_JSON).content(campaign))
                .andExpect(status().isCreated()).andReturn();
        long id = parse(created).path("id").asLong();

        mockMvc.perform(withToken(post("/api/creator/campaigns/" + id + "/submit"),
                tokenFor(creatorUser))).andExpect(status().isOk());

        mockMvc.perform(withToken(post("/api/admin/campaigns/" + id + "/approve"), tokenFor(admin))
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());

        MvcResult active = mockMvc.perform(withToken(
                        post("/api/restaurants/campaigns/" + id + "/approve"),
                        tokenFor(restaurantUser))
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACTIVE"))
                .andReturn();

        promoCode = parse(active).path("promoCode").asText();
    }

    @Test
    @DisplayName("a valid code can be looked up without recording anything")
    void lookupValidCode() throws Exception {
        JsonNode body = parse(mockMvc.perform(withToken(
                        get("/api/restaurants/scan/lookup?code=" + promoCode),
                        tokenFor(restaurantUser)))
                .andExpect(status().isOk()).andReturn());

        assertThat(body.path("promoCode").asText()).isEqualTo(promoCode);
        assertThat(body.path("campaignStatus").asText()).isEqualTo("ACTIVE");
        assertThat(body.path("validity").asText()).contains("Valid");
        assertThat(body.path("verifiedCustomers").asLong()).isZero();
    }

    @Test
    @DisplayName("a scan records interest but is not a verified customer")
    void scanDoesNotCountAsCustomer() throws Exception {
        JsonNode body = parse(mockMvc.perform(withToken(post("/api/restaurants/scan/scan"),
                        tokenFor(restaurantUser))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"promoCode\":\"%s\",\"method\":\"QR\"}".formatted(promoCode)))
                .andExpect(status().isCreated()).andReturn());

        assertThat(body.path("valid").asBoolean()).isTrue();
        // The key assertion: scanning leaves the verified-customer count untouched.
        assertThat(body.path("verifiedCustomers").asLong()).isZero();

        JsonNode after = parse(mockMvc.perform(withToken(
                        get("/api/restaurants/scan/lookup?code=" + promoCode),
                        tokenFor(restaurantUser)))
                .andExpect(status().isOk()).andReturn());
        assertThat(after.path("verifiedCustomers").asLong()).isZero();
        assertThat(after.path("redemptions").asLong()).isZero();
    }

    @Test
    @DisplayName("a redemption is the only thing that counts a verified customer")
    void redemptionCountsCustomer() throws Exception {
        JsonNode body = parse(mockMvc.perform(withToken(post("/api/restaurants/scan/redeem"),
                        tokenFor(restaurantUser))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"promoCode":"%s","customerRef":"guest-1","billPaise":200000,
                         "discountPaise":30000,"channel":"DINE_IN"}
                        """.formatted(promoCode)))
                .andExpect(status().isCreated()).andReturn());

        assertThat(body.path("verifiedCustomers").asLong()).isEqualTo(1);
        assertThat(body.path("totalRedemptions").asLong()).isEqualTo(1);
    }

    @Test
    @DisplayName("an invalid scan is recorded as invalid rather than silently dropped")
    void recordsInvalidScan() throws Exception {
        JsonNode body = parse(mockMvc.perform(withToken(post("/api/restaurants/scan/scan"),
                        tokenFor(restaurantUser))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"promoCode\":\"ZZ9999Z\"}"))
                .andExpect(status().isCreated()).andReturn());

        assertThat(body.path("valid").asBoolean()).isFalse();
        assertThat(body.path("message").asText()).isNotBlank();
    }

    @Test
    @DisplayName("another restaurant cannot redeem against this campaign")
    void crossRestaurantCannotRedeem() throws Exception {
        mockMvc.perform(withToken(post("/api/restaurants/scan/redeem"),
                        tokenFor(otherRestaurantUser))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"promoCode\":\"%s\",\"billPaise\":100000,\"discountPaise\":10000}"
                        .formatted(promoCode)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("a creator cannot record a scan")
    void creatorCannotScan() throws Exception {
        mockMvc.perform(withToken(post("/api/restaurants/scan/scan"), tokenFor(creatorUser))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"promoCode\":\"%s\"}".formatted(promoCode)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("a malformed code is rejected and an unknown well-formed code is not found")
    void rejectsBadCodes() throws Exception {
        mockMvc.perform(withToken(get("/api/restaurants/scan/lookup?code=NOPE"),
                        tokenFor(restaurantUser)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_FORMAT"));

        // A well-formed code that was never issued must be reported as unrecognised.
        mockMvc.perform(withToken(get("/api/restaurants/scan/lookup?code=QQ3005J"),
                        tokenFor(restaurantUser)))
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    @DisplayName("the same customer cannot redeem twice on one day")
    void preventsSameDayDuplicate() throws Exception {
        String body = """
                {"promoCode":"%s","customerRef":"guest-dup","billPaise":100000,
                 "discountPaise":10000}
                """.formatted(promoCode);

        mockMvc.perform(withToken(post("/api/restaurants/scan/redeem"), tokenFor(restaurantUser))
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());

        // The idempotency key is (campaign, customer, date), so the repeat is refused.
        // Which guard rejects first depends on the code's tier, so assert the reason set.
        mockMvc.perform(withToken(post("/api/restaurants/scan/redeem"), tokenFor(restaurantUser))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is4xxClientError())
                .andExpect(jsonPath("$.code").value(Matchers.anyOf(
                        Matchers.is("ALREADY_REDEEMED"),
                        Matchers.is("TIER_MONTHLY_LIMIT"))));
    }

    @Test
    @DisplayName("an exclusive E-J code is redeemable only once per restaurant per month")
    void exclusiveTierIsOncePerMonth() throws Exception {
        // The generator prefers an exclusive tier while one is unspent for this month.
        assertThat(promoCode.charAt(6)).isBetween('E', 'J');

        String body = """
                {"promoCode":"%s","customerRef":"guest-a","billPaise":100000,
                 "discountPaise":10000}
                """.formatted(promoCode);

        mockMvc.perform(withToken(post("/api/restaurants/scan/redeem"), tokenFor(restaurantUser))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());

        // A different customer on the same exclusive code is blocked for the rest of the month.
        mockMvc.perform(withToken(post("/api/restaurants/scan/redeem"), tokenFor(restaurantUser))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.replace("guest-a", "guest-b")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("TIER_MONTHLY_LIMIT"));
    }

    @Test
    @DisplayName("a rejected campaign cannot be redeemed")
    void rejectedCampaignCannotBeRedeem() throws Exception {
        // Built by concatenation rather than a text block, to keep the JSON escapes unambiguous.
        String campaign = "{\"restaurantId\":" + restaurant.getId()
                + ",\"title\":\"Doomed\",\"offerText\":\"10% off\""
                + ",\"discountPercent\":10,\"targetCustomers\":20,\"durationDays\":5"
                + ",\"startDate\":\"" + LocalDate.now().minusDays(1)
                + "\",\"endDate\":\"" + LocalDate.now().plusDays(10) + "\"}";

        MvcResult created = mockMvc.perform(withToken(post("/api/creator/campaigns"),
                        tokenFor(creatorUser))
                .contentType(MediaType.APPLICATION_JSON).content(campaign))
                .andExpect(status().isCreated()).andReturn();
        long doomed = parse(created).path("id").asLong();

        mockMvc.perform(withToken(post("/api/creator/campaigns/" + doomed + "/submit"),
                tokenFor(creatorUser))).andExpect(status().isOk());

        MvcResult rejected = mockMvc.perform(withToken(
                        post("/api/admin/campaigns/" + doomed + "/reject"), tokenFor(admin))
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"No\"}"))
                .andExpect(status().isOk()).andReturn();

        String body = "{\"promoCode\":\"" + parse(rejected).path("promoCode").asText()
                + "\",\"billPaise\":100000,\"discountPaise\":10000}";

        mockMvc.perform(withToken(post("/api/restaurants/scan/redeem"), tokenFor(restaurantUser))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CAMPAIGN_NOT_ACTIVE"));
    }
}
