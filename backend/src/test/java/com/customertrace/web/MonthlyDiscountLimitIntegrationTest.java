package com.customertrace.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import com.customertrace.domain.Creator;
import com.customertrace.domain.Restaurant;
import com.customertrace.domain.Role;
import com.customertrace.domain.User;
import com.customertrace.support.ConcurrencyTestBase;

/**
 * The monthly per-restaurant discount allowance.
 *
 * <p>B-F (2%-6%) are reusable. G-J (7%-10%) may each back at most one campaign per restaurant per
 * calendar month, and the cap is settled when the campaign is <em>submitted</em> rather than when it
 * is redeemed — otherwise a 50-customer target could never be served.
 *
 * <p>Deliberately not {@code @Transactional}: the ledger's unique key is the atomicity guarantee,
 * so these rows must really commit and real threads must really race on them. The concurrency
 * helper base owns fixture setup and cleanup for exactly this reason.
 */
class MonthlyDiscountLimitIntegrationTest extends ConcurrencyTestBase {

    private static final int THREADS = 6;

    private User creatorUser;
    private User secondCreatorUser;
    private Restaurant venue;
    private Restaurant rival;

    @BeforeEach
    void setUp() {
        for (Role role : new Role[] {
                new Role("ADMIN", "Administrator", "Platform governance"),
                new Role("RESTAURANT", "Restaurant", "Restaurant partner"),
                new Role("CREATOR", "Creator", "Content creator") }) {
            roleRepository.findByCode(role.getCode()).orElseGet(() -> roleRepository.save(role));
        }

        venue = createRestaurant("chef" + runId + "@restrnt", "Copper Kitchen", "Kochi");
        rival = createRestaurant("chef2" + runId + "@restrnt", "Silver Spoon", "Kochi");

        Creator star = createCreatorWithId("star" + runId + "@creator", "Star", "@star" + runId,
                venue);
        // The same star also works at the rival venue, so a cross-restaurant test can use one creator.
        assignCreator(star, rival);
        createCreatorWithId("star2" + runId + "@creator", "Star Two", "@star2" + runId, venue);

        creatorUser = userRepository.findByUserIdIgnoreCase("star" + runId + "@creator").orElseThrow();
        secondCreatorUser = userRepository.findByUserIdIgnoreCase("star2" + runId + "@creator")
                .orElseThrow();
    }

    @AfterEach
    void cleanUp() {
        tearDownWorld();
    }

    /** A campaign body for {@code restaurantId} at {@code discountPercent}, starting in October. */
    private String campaignJson(Long restaurantId, int discountPercent) {
        return """
                {"restaurantId":%d,"offerText":"Offer",
                 "discountPercent":%d,"targetCustomers":20,"durationDays":5,
                 "startDate":"2026-10-05","endDate":"2026-10-20"}
                """.formatted(restaurantId, discountPercent);
    }

    /** The same body but starting in the following calendar month. */
    private String campaignJsonNextMonth(Long restaurantId, int discountPercent) {
        return """
                {"restaurantId":%d,"offerText":"Offer",
                 "discountPercent":%d,"targetCustomers":20,"durationDays":5,
                 "startDate":"2026-11-01","endDate":"2026-11-20"}
                """.formatted(restaurantId, discountPercent);
    }

    /** Creates a draft and submits it, returning the submit status (201 then whatever). */
    private int submitStatus(User creator, String body) throws Exception {
        MvcResult created = mockMvc.perform(withToken(post("/api/creator/campaigns"),
                        tokenFor(creator))
                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
        if (created.getResponse().getStatus() != 201) {
            return created.getResponse().getStatus();
        }
        long id = parse(created).path("id").asLong();
        return mockMvc.perform(withToken(post("/api/creator/campaigns/" + id + "/submit"),
                tokenFor(creator))).andReturn().getResponse().getStatus();
    }

    private int submitStatus(User creator, Long restaurantId, int discountPercent)
            throws Exception {
        return submitStatus(creator, campaignJson(restaurantId, discountPercent));
    }

    private int ledgerRows(Restaurant restaurant, String month, String letter) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM promo_tier_ledger WHERE restaurant_id = ? "
                        + "AND calendar_month = ? AND tier_letter = ?",
                Integer.class, restaurant.getId(), month, letter);
        return count == null ? 0 : count;
    }

@Test
    @DisplayName("a reusable B-F discount may back many campaigns in one month")
    void reusableDiscountsHaveNoMonthlyCap() throws Exception {
        for (int i = 0; i < 3; i++) {
            assertThat(submitStatus(creatorUser, venue.getId(), 5))
                    .as("5%% (letter E) submission %d", i)
                    .isEqualTo(200);
        }

        // Nothing was claimed, because E is reusable.
        assertThat(ledgerRows(venue, "2026-10", "E")).isZero();
    }

    @Test
    @DisplayName("a restricted G-J discount backs only one campaign per month")
    void restrictedDiscountIsOncePerMonth() throws Exception {
        assertThat(submitStatus(creatorUser, venue.getId(), 9)).isEqualTo(200);
        assertThat(ledgerRows(venue, "2026-10", "I")).isEqualTo(1);

        // The second 9% campaign is refused rather than silently downgraded to another letter.
        MvcResult refused = mockMvc.perform(withToken(post("/api/creator/campaigns"),
                tokenFor(creatorUser)).contentType(MediaType.APPLICATION_JSON)
                .content(campaignJson(venue.getId(), 9))).andReturn();
        long secondId = parse(refused).path("id").asLong();
        mockMvc.perform(withToken(post("/api/creator/campaigns/" + secondId + "/submit"),
                        tokenFor(creatorUser)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MONTHLY_DISCOUNT_LIMIT"));

        assertThat(ledgerRows(venue, "2026-10", "I"))
                .as("a refused attempt must not consume a second allowance")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("each restricted letter has its own allowance")
    void eachRestrictedLetterIsCountedSeparately() throws Exception {
        // 7%, 8%, 9% and 10% are four letters, so all four may be issued once.
        assertThat(submitStatus(creatorUser, venue.getId(), 7)).isEqualTo(200);
        assertThat(submitStatus(creatorUser, venue.getId(), 8)).isEqualTo(200);
        assertThat(submitStatus(creatorUser, venue.getId(), 9)).isEqualTo(200);
        assertThat(submitStatus(creatorUser, venue.getId(), 10)).isEqualTo(200);

        assertThat(ledgerRows(venue, "2026-10", "G")).isEqualTo(1);
        assertThat(ledgerRows(venue, "2026-10", "H")).isEqualTo(1);
        assertThat(ledgerRows(venue, "2026-10", "I")).isEqualTo(1);
        assertThat(ledgerRows(venue, "2026-10", "J")).isEqualTo(1);

        // A fifth 7% campaign is now the one that is refused.
        assertThat(submitStatus(creatorUser, venue.getId(), 7)).isEqualTo(409);
    }

    @Test
    @DisplayName("the allowance is per restaurant, so two venues are independent")
    void allowancesAreIndependentPerRestaurant() throws Exception {
        assertThat(submitStatus(creatorUser, venue.getId(), 10)).isEqualTo(200);
        // The same 10% at a different restaurant is a different allowance.
        assertThat(submitStatus(creatorUser, rival.getId(), 10)).isEqualTo(200);

        assertThat(ledgerRows(venue, "2026-10", "J")).isEqualTo(1);
        assertThat(ledgerRows(rival, "2026-10", "J")).isEqualTo(1);
    }

    @Test
    @DisplayName("the allowance is shared across every creator assigned to the restaurant")
    void allowanceSpansAllCreators() throws Exception {
        assertThat(submitStatus(creatorUser, venue.getId(), 8)).isEqualTo(200);

        // A different creator, same restaurant, same month, same letter: still one allowance.
        assertThat(submitStatus(secondCreatorUser, venue.getId(), 8)).isEqualTo(409);
        assertThat(ledgerRows(venue, "2026-10", "H")).isEqualTo(1);
    }

    @Test
    @DisplayName("a new calendar month restores the allowance")
    void allowanceResetsOnANewMonth() throws Exception {
        assertThat(submitStatus(creatorUser, venue.getId(), 7)).isEqualTo(200);
        assertThat(ledgerRows(venue, "2026-10", "G")).isEqualTo(1);

        // The same restaurant and letter, but a campaign starting in the next month.
        assertThat(submitStatus(creatorUser, campaignJsonNextMonth(venue.getId(), 7)))
                .isEqualTo(200);

        assertThat(ledgerRows(venue, "2026-11", "G")).isEqualTo(1);
        assertThat(ledgerRows(venue, "2026-10", "G"))
                .as("the earlier month keeps its own record")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("an unrepresentable target or duration is refused without consuming anything")
    void invalidValuesDoNotConsumeTheAllowance() throws Exception {
        String tooMany = """
                {"restaurantId":%d,"offerText":"Offer",
                 "discountPercent":10,"targetCustomers":100,"durationDays":5,
                 "startDate":"2026-10-05","endDate":"2026-10-20"}
                """.formatted(venue.getId());
        mockMvc.perform(withToken(post("/api/creator/campaigns"), tokenFor(creatorUser))
                        .contentType(MediaType.APPLICATION_JSON).content(tooMany))
                .andExpect(status().isBadRequest());

        String zeroDuration = """
                {"restaurantId":%d,"offerText":"Offer",
                 "discountPercent":10,"targetCustomers":20,"durationDays":0,
                 "startDate":"2026-10-05","endDate":"2026-10-20"}
                """.formatted(venue.getId());
        mockMvc.perform(withToken(post("/api/creator/campaigns"), tokenFor(creatorUser))
                        .contentType(MediaType.APPLICATION_JSON).content(zeroDuration))
                .andExpect(status().isBadRequest());

        String unmappableDiscount = """
                {"restaurantId":%d,"offerText":"Offer",
                 "discountPercent":25,"targetCustomers":20,"durationDays":5,
                 "startDate":"2026-10-05","endDate":"2026-10-20"}
                """.formatted(venue.getId());
        mockMvc.perform(withToken(post("/api/creator/campaigns"), tokenFor(creatorUser))
                        .contentType(MediaType.APPLICATION_JSON).content(unmappableDiscount))
                .andExpect(status().isBadRequest());

        assertThat(ledgerRows(venue, "2026-10", "J"))
                .as("rejected requests must leave the allowance intact")
                .isZero();

        // And the allowance is genuinely still usable.
        assertThat(submitStatus(creatorUser, venue.getId(), 10)).isEqualTo(200);
    }

    @Test
    @DisplayName("concurrent submissions for the same restricted discount admit exactly one")
    void concurrentSubmissionsAdmitOne() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        try {
            // All racers hit the same restaurant, month and letter. The unique key on
            // (restaurant, month, letter) is what makes exactly one of them win.
            List<Future<Integer>> futures = new ArrayList<>();
            for (int i = 0; i < THREADS; i++) {
                User actor = i % 2 == 0 ? creatorUser : secondCreatorUser;
                futures.add(pool.submit(() -> submitStatusQuietly(actor, venue.getId(), 10)));
            }

            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> future : futures) {
                statuses.add(future.get(30, TimeUnit.SECONDS));
            }

            assertThat(statuses.stream().filter(s -> s == 200).count())
                    .as("statuses=%s", statuses)
                    .isEqualTo(1);
            // A loser must see the domain conflict, never a 500 from the unique key.
            assertThat(statuses.stream().filter(s -> s >= 500).count())
                    .as("statuses=%s", statuses)
                    .isZero();
        } finally {
            pool.shutdownNow();
            pool.awaitTermination(5, TimeUnit.SECONDS);
        }

        assertThat(ledgerRows(venue, "2026-10", "J"))
                .as("exactly one ledger row, whichever racer won")
                .isEqualTo(1);
    }
    /**
     * Submits without letting an exception escape, so a racer that loses the ledger race returns a
     * status instead of poisoning the shared executor.
     */
    private int submitStatusQuietly(User creator, Long restaurantId, int discountPercent) {
        try {
            return submitStatus(creator, campaignJson(restaurantId, discountPercent));
        } catch (Exception e) {
            return 599;
        }
    }
}