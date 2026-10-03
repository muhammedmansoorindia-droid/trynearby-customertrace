package com.customertrace.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import com.customertrace.domain.Restaurant;
import com.customertrace.domain.Subscription;
import com.customertrace.domain.SubscriptionPlan;
import com.customertrace.domain.User;
import com.customertrace.repository.SubscriptionPlanRepository;
import com.customertrace.repository.SubscriptionRepository;
import com.customertrace.support.IntegrationTestBase;

/**
 * Subscription reads, plan changes and status transitions.
 *
 * <p>Emphasis is on the two things most likely to be wrong in production: a restaurant reaching
 * another tenant's subscription, and a status transition that should be refused being applied
 * anyway.
 */
class SubscriptionIntegrationTest extends IntegrationTestBase {

    @Autowired
    private SubscriptionRepository subscriptions;

    @Autowired
    private SubscriptionPlanRepository plans;

    private User admin;
    private Restaurant owner;
    private Restaurant rival;
    private User ownerUser;
    private User rivalUser;
    private SubscriptionPlan basic;
    private SubscriptionPlan pro;

    @BeforeEach
    void setUp() {
        admin = createUser("admin-sub@Trynearby", "ADMIN", "Admin");
        owner = createRestaurant("owner-sub@restrnt", "Owner Kitchen", "Kochi");
        rival = createRestaurant("rival-sub@restrnt", "Rival Kitchen", "Kochi");
        ownerUser = userRepository.findByUserIdIgnoreCase("owner-sub@restrnt").orElseThrow();
        rivalUser = userRepository.findByUserIdIgnoreCase("rival-sub@restrnt").orElseThrow();

        basic = plan("BASIC", "Basic", 99_900L);
        pro = plan("PRO", "Pro", 299_900L);
    }

    private SubscriptionPlan plan(String code, String name, long price) {
        SubscriptionPlan p = new SubscriptionPlan();
        p.setCode(code);
        p.setName(name);
        p.setMonthlyPricePaise(price);
        p.setMaxCampaigns(5);
        p.setFeatures("Feature list");
        p.setActive(true);
        return plans.save(p);
    }

    private Subscription subscribe(Restaurant restaurant, SubscriptionPlan plan,
                                   String status) {
        Subscription s = new Subscription();
        s.setRestaurantId(restaurant.getId());
        s.setPlanId(plan.getId());
        s.setStatus(status);
        s.setBillingCycle("MONTHLY");
        s.setAmountPaise(plan.getMonthlyPricePaise());
        return subscriptions.save(s);
    }

    @Test
    @DisplayName("a restaurant sees its own current subscription")
    void currentSubscription() throws Exception {
        subscribe(owner, basic, "ACTIVE");
        flush();

        mockMvc.perform(withToken(get("/api/restaurant/subscription"), tokenFor(ownerUser)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.planCode").value("BASIC"))
                .andExpect(jsonPath("$.entitled").value(true))
                .andExpect(jsonPath("$.amountPaise").value(99_900L));
    }

    @Test
    @DisplayName("a restaurant without a subscription gets 404, not an empty object")
    void missingSubscription() throws Exception {
        mockMvc.perform(withToken(get("/api/restaurant/subscription"), tokenFor(rivalUser)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("a restaurant cannot read another restaurant's subscription")
    void crossTenantReadDenied() throws Exception {
        subscribe(rival, pro, "ACTIVE");
        flush();

        mockMvc.perform(withToken(get("/api/restaurant/subscription"), tokenFor(ownerUser)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("active plans are listed for every signed-in user")
    void plansAreListed() throws Exception {
        mockMvc.perform(withToken(get("/api/plans"), tokenFor(ownerUser)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].code").value("BASIC"));
    }

    @Test
    @DisplayName("an administrator can change a plan and the price follows the plan")
    void adminChangesPlan() throws Exception {
        Subscription s = subscribe(owner, basic, "ACTIVE");
        flush();

        mockMvc.perform(withToken(
                        put("/api/admin/subscriptions/" + s.getId() + "/plan"), tokenFor(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"planId\":" + pro.getId() + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.planCode").value("PRO"))
                // The amount comes from the plan, so a caller cannot dictate the price.
                .andExpect(jsonPath("$.amountPaise").value(299_900L));
    }

    @Test
    @DisplayName("changing a plan to an unknown plan is rejected")
    void changeToUnknownPlan() throws Exception {
        Subscription s = subscribe(owner, basic, "ACTIVE");
        flush();

        mockMvc.perform(withToken(
                        put("/api/admin/subscriptions/" + s.getId() + "/plan"), tokenFor(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"planId\":999999}"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("a restaurant cannot administer any subscription")
    void restaurantCannotAdministerSubscriptions() throws Exception {
        Subscription s = subscribe(owner, basic, "ACTIVE");
        flush();

        mockMvc.perform(withToken(
                        put("/api/admin/subscriptions/" + s.getId() + "/plan"),
                        tokenFor(ownerUser))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"planId\":" + pro.getId() + "}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(withToken(
                        patch("/api/admin/subscriptions/" + s.getId() + "/status"),
                        tokenFor(ownerUser))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"CANCELLED\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("ACTIVE may move to PAST_DUE and back")
    void validStatusTransitions() throws Exception {
        Subscription s = subscribe(owner, basic, "ACTIVE");
        flush();

        mockMvc.perform(withToken(patch("/api/admin/subscriptions/" + s.getId() + "/status"),
                        tokenFor(admin)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"PAST_DUE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAST_DUE"));

        mockMvc.perform(withToken(patch("/api/admin/subscriptions/" + s.getId() + "/status"),
                        tokenFor(admin)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"ACTIVE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    @DisplayName("CANCELLED is terminal and cannot be revived")
    void cancelledIsTerminal() throws Exception {
        Subscription s = subscribe(owner, basic, "ACTIVE");
        flush();

        mockMvc.perform(withToken(patch("/api/admin/subscriptions/" + s.getId() + "/status"),
                        tokenFor(admin)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"CANCELLED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.cancelledAt").isNotEmpty())
                .andExpect(jsonPath("$.entitled").value(false));

        mockMvc.perform(withToken(patch("/api/admin/subscriptions/" + s.getId() + "/status"),
                        tokenFor(admin)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"ACTIVE\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATUS_TRANSITION"));
        flush();
        // The refused transition must not have been partially applied.
        assertThat(subscriptions.findById(s.getId()).orElseThrow().getStatus())
                .isEqualTo("CANCELLED");
    }

    @Test
    @DisplayName("an unknown status is rejected before any write")
    void unknownStatusRejected() throws Exception {
        Subscription s = subscribe(owner, basic, "ACTIVE");
        flush();

        mockMvc.perform(withToken(patch("/api/admin/subscriptions/" + s.getId() + "/status"),
                        tokenFor(admin)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"BANANA\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("UNKNOWN_STATUS"));
        flush();
        assertThat(subscriptions.findById(s.getId()).orElseThrow().getStatus())
                .isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("an absent status is a bad request")
    void missingStatusRejected() throws Exception {
        Subscription s = subscribe(owner, basic, "ACTIVE");
        flush();

        mockMvc.perform(withToken(patch("/api/admin/subscriptions/" + s.getId() + "/status"),
                        tokenFor(admin)).contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("STATUS_REQUIRED"));
    }

    @Test
    @DisplayName("subscription endpoints require authentication")
    void requiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/restaurant/subscription")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/plans")).andExpect(status().isUnauthorized());
    }
}