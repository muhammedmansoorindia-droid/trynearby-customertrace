package com.customertrace.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import com.customertrace.domain.Creator;
import com.customertrace.domain.Restaurant;
import com.customertrace.domain.User;
import com.customertrace.support.ConcurrencyTestBase;

/**
 * Concurrency behaviour of the redemption path.
 *
 * <p>Threads are synchronised with {@link CountDownLatch} barriers rather than sleeps, so each
 * test makes a genuine simultaneous attempt. Assertions are made against <em>persisted database
 * state</em>, not only HTTP responses, so a transaction that reported success but rolled back
 * would still be caught.
 *
 * <p>The guarantees come from database constraints — {@code uk_redemption_daily} and
 * {@code promo_tier_ledger.uk_tier_restaurant_month} — so an application-level check that loses
 * the race cannot admit a duplicate.
 */
class RedemptionConcurrencyIntegrationTest extends ConcurrencyTestBase {

    private static final int THREADS = 8;

    private User admin;
    private User restaurantUser;
    private Creator creator;
    private Restaurant restaurant;
    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        admin = createUser("boss" + runId + "@Trynearby", "ADMIN", "Boss");
        restaurant = createRestaurant("chef" + runId + "@restrnt", "Copper Kitchen", "Kochi");
        createCreator("star" + runId + "@creator", "Star", "@star" + runId);
        restaurantUser = userRepository.findByUserIdIgnoreCase("chef" + runId + "@restrnt")
                .orElseThrow();
        creator = creatorRepository.findAll().stream()
                .filter(c -> c.getHandle().endsWith(runId)).findFirst().orElseThrow();
        executor = Executors.newFixedThreadPool(THREADS);
    }

    @AfterEach
    void tearDownPool() throws Exception {
        if (executor != null) {
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
        tearDownWorld();
    }

    /** Builds an approved, in-date campaign and returns its id. */
    private long activeCampaign(int targetCustomers) throws Exception {
        return activeCampaign(restaurant, restaurantUser, targetCustomers);
    }

    /** Same as above but for a specific restaurant, so tier caps cannot collide. */
    private long activeCampaign(Restaurant owner, User approver, int targetCustomers)
            throws Exception {
        String body = "{\"restaurantId\":" + owner.getId()
                + ",\"title\":\"Concurrent\",\"offerText\":\"15% off\""
                + ",\"discountPercent\":15,\"targetCustomers\":" + targetCustomers
                + ",\"durationDays\":10,\"startDate\":\""
                + java.time.LocalDate.now().minusDays(1)
                + "\",\"endDate\":\"" + java.time.LocalDate.now().plusDays(30) + "\"}";

        User creatorUser = userRepository.findByUserIdIgnoreCase("star" + runId + "@creator")
                .orElseThrow();
        MvcResult created = mockMvc.perform(withToken(post("/api/creator/campaigns"),
                        tokenFor(creatorUser))
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        long id = parse(created).path("id").asLong();

        mockMvc.perform(withToken(post("/api/creator/campaigns/" + id + "/submit"),
                tokenFor(creatorUser))).andExpect(status().isOk());
        mockMvc.perform(withToken(post("/api/admin/campaigns/" + id + "/approve"), tokenFor(admin))
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());
        mockMvc.perform(withToken(post("/api/restaurants/campaigns/" + id + "/approve"),
                        tokenFor(approver))
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());
        return id;
    }

    /** A redeem request body for one customer. */
    private String redeemBody(String code, String customerRef) {
        return "{\"promoCode\":\"" + code + "\",\"customerRef\":\"" + customerRef
                + "\",\"billPaise\":100000,\"discountPaise\":10000}";
    }

    /**
     * Runs {@code count} tasks that all park on one start barrier, then collects their statuses.
     */
    private List<Integer> runSimultaneously(int count, Callable<Integer> task) throws Exception {
        CountDownLatch ready = new CountDownLatch(count);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();

        for (int i = 0; i < count; i++) {
            futures.add(executor.submit(() -> {
                // Each thread parks here, so all requests are genuinely in flight together.
                ready.countDown();
                start.await(10, TimeUnit.SECONDS);
                return task.call();
            }));
        }

        assertThat(ready.await(10, TimeUnit.SECONDS))
                .withFailMessage("Workers did not reach the barrier in time").isTrue();
        start.countDown();

        List<Integer> statuses = new ArrayList<>();
        for (Future<Integer> future : futures) {
            statuses.add(future.get(30, TimeUnit.SECONDS));
        }
        return statuses;
    }

@Test
    @DisplayName("simultaneous redemptions of one code respect the code's own tier limit")
    void simultaneousRedemptionsRespectTierLimit() throws Exception {
        long campaignId = activeCampaign(50);
        String code = promoCodeFor(campaignId);
        assertThat(code).isNotBlank();

        AtomicInteger counter = new AtomicInteger();
        List<Integer> statuses = runSimultaneously(THREADS, () ->
                mockMvc.perform(withToken(post("/api/restaurants/scan/redeem"),
                                tokenFor(restaurantUser))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(redeemBody(code, "guest-" + counter.getAndIncrement())))
                        .andReturn().getResponse().getStatus());

        // A-D tiers are reusable, so every distinct customer is a genuine visit. E-J codes are
        // capped at one redemption per restaurant per calendar month, so only the first wins.
        boolean exclusive = code.charAt(6) >= 'E' && code.charAt(6) <= 'J';
        int expected = exclusive ? 1 : THREADS;

        long created = statuses.stream().filter(s -> s == 201).count();
        assertThat(created).withFailMessage("statuses=%s tier=%s", statuses, code.charAt(6))
                .isEqualTo(expected);
        // Losers must receive a domain error, never a 500 from a constraint violation.
        assertThat(statuses).filteredOn(s -> s >= 500).isEmpty();
        assertThat(redemptionCount(campaignId)).isEqualTo(expected);
    }

    @Test
    @DisplayName("the same customer racing themselves yields exactly one redemption")
    void sameCustomerRaceYieldsOneRedemption() throws Exception {
        long campaignId = activeCampaign(50);
        String code = promoCodeFor(campaignId);

        List<Integer> statuses = runSimultaneously(THREADS, () ->
                mockMvc.perform(withToken(post("/api/restaurants/scan/redeem"),
                                tokenFor(restaurantUser))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(redeemBody(code, "guest-duplicate")))
                        .andReturn().getResponse().getStatus());

        // uk_redemption_daily admits exactly one; a loser must never surface as a 500.
        assertThat(statuses).filteredOn(s -> s == 201).hasSize(1);
        assertThat(statuses).filteredOn(s -> s >= 500).isEmpty();
        assertThat(redemptionCount(campaignId)).isEqualTo(1);
    }

    @Test
    @DisplayName("an E-J code cannot be redeemed twice by one restaurant in the same month")
    void exclusiveTierRaceAdmitsOneRedemption() throws Exception {
        long campaignId = activeCampaign(50);
        String code = promoCodeFor(campaignId);

        // Only meaningful for an exclusive tier; the generator prefers one when available.
        if (code.charAt(6) < 'E' || code.charAt(6) > 'J') {
            return;
        }

        AtomicInteger counter = new AtomicInteger();
        List<Integer> statuses = runSimultaneously(THREADS, () ->
                mockMvc.perform(withToken(post("/api/restaurants/scan/redeem"),
                                tokenFor(restaurantUser))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(redeemBody(code, "tier-" + counter.getAndIncrement())))
                        .andReturn().getResponse().getStatus());

        assertThat(statuses).filteredOn(s -> s == 201).hasSize(1);
        // A losing racer must surface the domain error, never a 500 from the unique key.
        assertThat(statuses).filteredOn(s -> s >= 500).isEmpty();

        Integer ledgerRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM promo_tier_ledger WHERE restaurant_id = ? "
                        + "AND tier_letter = ?",
                Integer.class, restaurant.getId(), String.valueOf(code.charAt(6)));
        assertThat(ledgerRows).isEqualTo(1);
        assertThat(redemptionCount(campaignId)).isEqualTo(1);
    }
@Test
    @DisplayName("concurrent redemptions on independent campaigns are not blocked by each other")
    void independentCampaignsDoNotBlockEachOther() throws Exception {
        // Two separate restaurants: the E-J monthly tier cap is per restaurant per month, so
        // sharing one restaurant could legitimately let one redemption lose the tier race and
        // make this assertion about campaign independence meaningless.
        Restaurant second = createRestaurant("chef2" + runId + "@restrnt", "Silver Spoon",
                "Kochi");
        User secondUser = userRepository.findByUserIdIgnoreCase("chef2" + runId + "@restrnt")
                .orElseThrow();

        long first = activeCampaign(20);
        long second2 = activeCampaign(second, secondUser, 20);
        String firstCode = promoCodeFor(first);
        String secondCode = promoCodeFor(second2);
        assertThat(firstCode).isNotBlank().isNotEqualTo(secondCode);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        for (var attempt : List.of(
                new Object[] {firstUser(), firstCode, "guest-x"},
                new Object[] {secondUser, secondCode, "guest-x"})) {
            User actor = (User) attempt[0];
            String code = (String) attempt[1];
            String guest = (String) attempt[2];
            futures.add(executor.submit(() -> {
                ready.countDown();
                start.await(10, TimeUnit.SECONDS);
                return mockMvc.perform(withToken(post("/api/restaurants/scan/redeem"),
                                tokenFor(actor))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(redeemBody(code, guest)))
                        .andReturn().getResponse().getStatus();
            }));
        }
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();

        for (Future<Integer> future : futures) {
            assertThat(future.get(30, TimeUnit.SECONDS)).isEqualTo(201);
        }
        assertThat(redemptionCount(first)).isEqualTo(1);
        assertThat(redemptionCount(second2)).isEqualTo(1);
    }

    private User firstUser() {
        return restaurantUser;
    }

    @Test
    @DisplayName("concurrent scans never inflate the verified-customer count")
    void scansDoNotInflateVerifiedCustomers() throws Exception {
        long campaignId = activeCampaign(50);
        String code = promoCodeFor(campaignId);

        // Eight simultaneous scans. Interest is not a customer, so nothing may be recorded
        // as a redemption and no verified-customer metric may move.
        List<Integer> statuses = runSimultaneously(THREADS, () ->
                mockMvc.perform(withToken(post("/api/restaurants/scan/scan"),
                                tokenFor(restaurantUser))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"promoCode\":\"" + code + "\",\"method\":\"QR\"}"))
                        .andReturn().getResponse().getStatus());

        assertThat(statuses).allMatch(s -> s == 201);

        Integer scans = jdbc.queryForObject(
                "SELECT COUNT(*) FROM scan_events WHERE campaign_id = ? AND valid = true",
                Integer.class, campaignId);
        assertThat(scans).isEqualTo(THREADS);
        assertThat(redemptionCount(campaignId)).isZero();
    }
}
