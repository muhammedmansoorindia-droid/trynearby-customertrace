package com.customertrace.service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.customertrace.domain.Notification;
import com.customertrace.domain.Restaurant;
import com.customertrace.domain.Subscription;
import com.customertrace.domain.SubscriptionPlan;
import com.customertrace.exception.ApiErrors;
import com.customertrace.repository.NotificationRepository;
import com.customertrace.repository.RestaurantRepository;
import com.customertrace.repository.SubscriptionPlanRepository;
import com.customertrace.repository.SubscriptionRepository;
import com.customertrace.security.CurrentUser;
import com.customertrace.web.dto.SubscriptionDtos;

/**
 * Restaurant subscriptions.
 *
 * <p><b>No payments.</b> This service records plan and status changes that an administrator makes
 * directly; it never contacts a provider and never records a payment as having happened.
 *
 * <p>Status changes follow an explicit allow-list rather than ad-hoc checks, so an illegal
 * transition is rejected instead of silently applied.
 */
@Service
public class SubscriptionService {

    /** Statuses that count as live for entitlement purposes. */
    public static final Set<String> ENTITLING = Set.of("TRIALING", "ACTIVE");

    /** Which status a subscription may move to from each current status. */
    private static final Map<String, Set<String>> ALLOWED_TRANSITIONS = Map.of(
            "TRIALING", Set.of("ACTIVE", "CANCELLED"),
            "ACTIVE", Set.of("PAST_DUE", "CANCELLED"),
            "PAST_DUE", Set.of("ACTIVE", "CANCELLED"),
            // Terminal: a cancelled subscription cannot be revived by this API.
            "CANCELLED", Set.of());

    private final SubscriptionRepository subscriptions;
    private final SubscriptionPlanRepository plans;
    private final RestaurantRepository restaurants;
    private final NotificationRepository notifications;

    public SubscriptionService(SubscriptionRepository subscriptions,
                               SubscriptionPlanRepository plans,
                               RestaurantRepository restaurants,
                               NotificationRepository notifications) {
        this.subscriptions = subscriptions;
        this.plans = plans;
        this.restaurants = restaurants;
        this.notifications = notifications;
    }

    // ── Reads ───────────────────────────────────────────────────────────────

    /** The restaurant's own current subscription; 404 when it has never subscribed. */
    @Transactional(readOnly = true)
    public SubscriptionDtos.SubscriptionResponse current(CurrentUser actor) {
        Restaurant restaurant = ownRestaurant(actor);
        return subscriptions.findFirstByRestaurantIdAndStatusInOrderByCreatedAtDesc(
                        restaurant.getId(), List.copyOf(ENTITLING))
                .or(() -> subscriptions.findByRestaurantIdOrderByCreatedAtDesc(
                        restaurant.getId()).stream().findFirst())
                .map(s -> toResponse(s, plans.findById(s.getPlanId()).orElse(null)))
                .orElseThrow(() -> ApiErrors.notFound(
                        "This restaurant has no subscription yet."));
    }

    @Transactional(readOnly = true)
    public List<SubscriptionDtos.SubscriptionResponse> history(CurrentUser actor) {
        Restaurant restaurant = ownRestaurant(actor);
        return subscriptions.findByRestaurantIdOrderByCreatedAtDesc(restaurant.getId())
                .stream()
                .map(s -> toResponse(s, plans.findById(s.getPlanId()).orElse(null)))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<SubscriptionDtos.PlanResponse> activePlans() {
        return plans.findByActiveTrueOrderByMonthlyPricePaiseAsc().stream()
                .map(SubscriptionService::toPlanResponse)
                .toList();
    }

    // ── Writes ──────────────────────────────────────────────────────────────

    /**
     * Moves a subscription to a new plan, keeping its status.
     *
     * <p>The amount is taken from the plan, not from the request, so a caller cannot dictate the
     * price.
     */
    @Transactional
    public SubscriptionDtos.SubscriptionResponse changePlan(CurrentUser actor, Long subscriptionId,
                                                           Long planId) {
        Subscription subscription = requireOwnSubscription(actor, subscriptionId);
        SubscriptionPlan plan = plans.findById(planId)
                .orElseThrow(() -> ApiErrors.notFound("Plan " + planId + " was not found."));
        if (!plan.isActive()) {
            throw ApiErrors.badRequest("PLAN_INACTIVE", "That plan is no longer offered.");
        }

        subscription.setPlanId(plan.getId());
        subscription.setAmountPaise(plan.getMonthlyPricePaise());
        subscription.setUpdatedAt(Instant.now());
        subscriptions.save(subscription);

        notifyRestaurant(subscription, "Subscription plan changed",
                "Your subscription now uses the " + plan.getName() + " plan.");
        return toResponse(subscription, plan);
    }

    /**
     * Applies a status change after checking it against {@link #ALLOWED_TRANSITIONS}.
     *
     * <p>Re-applying the current status is a no-op so a retried request cannot fail.
     */
    @Transactional
    public SubscriptionDtos.SubscriptionResponse changeStatus(CurrentUser actor,
                                                              Long subscriptionId,
                                                              String requestedStatus) {
        Subscription subscription = requireOwnSubscription(actor, subscriptionId);
        String target = normaliseStatus(requestedStatus);

        if (target.equals(subscription.getStatus())) {
            return toResponse(subscription,
                    plans.findById(subscription.getPlanId()).orElse(null));
        }

        Set<String> allowed = ALLOWED_TRANSITIONS.getOrDefault(subscription.getStatus(),
                Set.of());
        if (!allowed.contains(target)) {
            throw ApiErrors.conflict("INVALID_STATUS_TRANSITION",
                    "A subscription cannot move from " + subscription.getStatus() + " to "
                            + target + ".");
        }

        Instant now = Instant.now();
        subscription.setStatus(target);
        subscription.setUpdatedAt(now);
        if ("CANCELLED".equals(target)) {
            subscription.setCancelledAt(now);
            subscription.setRenewsAt(null);
        } else if ("ACTIVE".equals(target)) {
            subscription.setRenewsAt(now.plus(30, ChronoUnit.DAYS));
        }
        subscriptions.save(subscription);

        notifyRestaurant(subscription, "Subscription " + target.toLowerCase(),
                "Your subscription status is now " + target + ".");
        return toResponse(subscription,
                plans.findById(subscription.getPlanId()).orElse(null));
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private static String normaliseStatus(String raw) {
        if (raw == null || raw.isBlank()) {
            throw ApiErrors.badRequest("STATUS_REQUIRED", "A status is required.");
        }
        String value = raw.trim().toUpperCase();
        if (!ALLOWED_TRANSITIONS.containsKey(value)) {
            throw ApiErrors.badRequest("UNKNOWN_STATUS",
                    "Unknown subscription status: " + raw);
        }
        return value;
    }

    private Restaurant ownRestaurant(CurrentUser actor) {
        return restaurants.findByUserId(actor.userId()).orElseThrow(
                () -> ApiErrors.forbidden("This endpoint requires a restaurant account."));
    }

    /**
     * Loads the subscription and proves the caller may act on it.
     *
     * <p>A restaurant may only touch its own row; an administrator may touch any. For a
     * cross-tenant restaurant we answer 404 rather than 403 so ids cannot be probed.
     */
    private Subscription requireOwnSubscription(CurrentUser actor, Long subscriptionId) {
        Subscription subscription = subscriptions.findById(subscriptionId)
                .orElseThrow(() -> ApiErrors.notFound(
                        "Subscription " + subscriptionId + " was not found."));
        if (actor.isAdmin()) {
            return subscription;
        }
        Restaurant restaurant = ownRestaurant(actor);
        if (!subscription.getRestaurantId().equals(restaurant.getId())) {
            throw ApiErrors.notFound("Subscription " + subscriptionId + " was not found.");
        }
        return subscription;
    }

    private void notifyRestaurant(Subscription subscription, String title, String body) {
        restaurants.findById(subscription.getRestaurantId()).ifPresent(r -> {
            Notification n = new Notification();
            n.setType("SUBSCRIPTION");
            n.setAudience("RESTAURANT");
            n.setRecipientId(r.getUserId());
            n.setTitle(title);
            n.setBody(body);
            n.setLink("/restaurant/billing");
            n.setCreatedAt(Instant.now());
            notifications.save(n);
        });
    }

    static SubscriptionDtos.SubscriptionResponse toResponse(Subscription s,
                                                            SubscriptionPlan plan) {
        return new SubscriptionDtos.SubscriptionResponse(s.getId(), s.getRestaurantId(),
                plan != null ? plan.getCode() : null, plan != null ? plan.getName() : null,
                s.getStatus(), s.getBillingCycle(), s.getAmountPaise(), s.isEntitled(),
                s.getStartedAt(), s.getRenewsAt(), s.getCancelledAt(), s.getUpdatedAt());
    }

    static SubscriptionDtos.PlanResponse toPlanResponse(SubscriptionPlan p) {
        return new SubscriptionDtos.PlanResponse(p.getId(), p.getCode(), p.getName(),
                p.getMonthlyPricePaise(), p.getFeatures(), p.getMaxCampaigns());
    }
}