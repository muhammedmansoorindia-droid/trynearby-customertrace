package com.customertrace.service;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.customertrace.domain.Creator;
import com.customertrace.domain.Restaurant;
import com.customertrace.domain.RestaurantCreatorAssignment;
import com.customertrace.domain.Subscription;
import com.customertrace.domain.SubscriptionPlan;
import com.customertrace.domain.User;
import com.customertrace.exception.ApiErrors;
import com.customertrace.repository.CampaignRepository;
import com.customertrace.repository.CreatorRepository;
import com.customertrace.repository.RestaurantCreatorAssignmentRepository;
import com.customertrace.repository.RestaurantRepository;
import com.customertrace.repository.SubscriptionPlanRepository;
import com.customertrace.repository.SubscriptionRepository;
import com.customertrace.repository.UserRepository;
import com.customertrace.web.dto.AssignmentDtos;
import com.customertrace.web.dto.SubscriptionDtos;

/**
 * Platform administration of restaurants, creators and subscriptions.
 *
 * <p>Separate from {@link AssignmentService} on purpose: assignments decide who may run a campaign
 * for whom, whereas this decides whether a profile or a subscription is in good standing. An
 * administrator suspends a restaurant here; an assignment is ended separately and neither implies
 * the other.
 */
@Service
public class AdminDirectoryService {

    /** Profile statuses an administrator may set. Anything else is rejected outright. */
    static final Set<String> PROFILE_STATUSES = Set.of("ACTIVE", "SUSPENDED");

    private final RestaurantRepository restaurants;
    private final CreatorRepository creators;
    private final UserRepository users;
    private final SubscriptionRepository subscriptions;
    private final SubscriptionPlanRepository plans;
    private final RestaurantCreatorAssignmentRepository assignments;
    private final CampaignRepository campaigns;

    public AdminDirectoryService(RestaurantRepository restaurants, CreatorRepository creators,
                                 UserRepository users, SubscriptionRepository subscriptions,
                                 SubscriptionPlanRepository plans,
                                 RestaurantCreatorAssignmentRepository assignments,
                                 CampaignRepository campaigns) {
        this.restaurants = restaurants;
        this.creators = creators;
        this.users = users;
        this.subscriptions = subscriptions;
        this.plans = plans;
        this.assignments = assignments;
        this.campaigns = campaigns;
    }

    // ── Restaurants ──────────────────────────────────────────────────────────

    /** Every restaurant with its status, subscription and campaign counts. */
    @Transactional(readOnly = true)
    public List<AssignmentDtos.RestaurantSummary> restaurants() {
        // Counted in memory rather than with a per-row query, so the list stays one round trip.
        Map<Long, Long> campaignsByRestaurant = campaigns.findAll().stream()
                .collect(Collectors.groupingBy(c -> c.getRestaurantId(), Collectors.counting()));
        Map<Long, Long> creatorsByRestaurant = assignments.findAll().stream()
                .filter(RestaurantCreatorAssignment::isActive)
                .collect(Collectors.groupingBy(RestaurantCreatorAssignment::getRestaurantId,
                        Collectors.counting()));

        return restaurants.findAll().stream()
                .sorted(Comparator.comparing(Restaurant::isActive).reversed()
                        .thenComparing(Restaurant::getName,
                                Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
                .map(r -> {
                    Subscription current = currentSubscription(r.getId());
                    SubscriptionPlan plan = current == null ? null
                            : plans.findById(current.getPlanId()).orElse(null);
                    return new AssignmentDtos.RestaurantSummary(
                            r.getId(), r.getName(), r.getCity(), r.getRegion(), r.getStatus(),
                            r.getRatingCount(),
                            r.getRatingCount() == 0 ? null
                                    : (double) r.getRatingSum() / r.getRatingCount(),
                            plan == null ? null : plan.getName(),
                            current == null ? null : current.getStatus(),
                            creatorsByRestaurant.getOrDefault(r.getId(), 0L).intValue(),
                            campaignsByRestaurant.getOrDefault(r.getId(), 0L),
                            userIdOf(r.getUserId()));
                })
                .toList();
    }

    /**
     * Suspends or reactivates a restaurant.
     *
     * <p>Suspending hides the venue from new work but deletes nothing: campaigns, bookings and
     * assignment history stay exactly as they are, so reactivating is a true undo.
     */
    @Transactional
    public AssignmentDtos.RestaurantSummary setRestaurantStatus(Long restaurantId, String rawStatus) {
        Restaurant restaurant = restaurants.findById(restaurantId).orElseThrow(
                () -> ApiErrors.notFound("Restaurant " + restaurantId + " was not found."));
        restaurant.setStatus(normaliseProfileStatus(rawStatus));
        restaurant.setUpdatedAt(Instant.now());
        restaurants.save(restaurant);
        return restaurants().stream()
                .filter(r -> r.id().equals(restaurantId)).findFirst()
                .orElseThrow(() -> ApiErrors.notFound(
                        "Restaurant " + restaurantId + " was not found."));
    }

    // ── Creators ─────────────────────────────────────────────────────────────

    /** Every creator with their profile status and how much work they currently hold. */
    @Transactional(readOnly = true)
    public List<AssignmentDtos.CreatorSummary> creators() {
        Map<Long, Long> campaignsByCreator = campaigns.findAll().stream()
                .collect(Collectors.groupingBy(c -> c.getCreatorId(), Collectors.counting()));
        Map<Long, Long> venuesByCreator = assignments.findAll().stream()
                .filter(RestaurantCreatorAssignment::isActive)
                .collect(Collectors.groupingBy(RestaurantCreatorAssignment::getCreatorId,
                        Collectors.counting()));

        return creators.findAll().stream()
                .sorted(Comparator.comparing(Creator::isActive).reversed()
                        .thenComparing(Creator::getDisplayName,
                                Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
                .map(c -> new AssignmentDtos.CreatorSummary(
                        c.getId(), c.getDisplayName(), c.getHandle(), c.getCity(), c.getNiche(),
                        c.getFollowers(), c.isVerified(), c.getStatus(),
                        venuesByCreator.getOrDefault(c.getId(), 0L).intValue(),
                        campaignsByCreator.getOrDefault(c.getId(), 0L),
                        userIdOf(c.getUserId())))
                .toList();
    }

    /** Suspends or reactivates a creator profile. */
    @Transactional
    public AssignmentDtos.CreatorSummary setCreatorStatus(Long creatorId, String rawStatus) {
        Creator creator = creators.findById(creatorId).orElseThrow(
                () -> ApiErrors.notFound("Creator " + creatorId + " was not found."));
        creator.setStatus(normaliseProfileStatus(rawStatus));
        creator.setUpdatedAt(Instant.now());
        creators.save(creator);
        return creators().stream()
                .filter(c -> c.id().equals(creatorId)).findFirst()
                .orElseThrow(() -> ApiErrors.notFound("Creator " + creatorId + " was not found."));
    }

    // ── Subscriptions ────────────────────────────────────────────────────────

    /**
     * Every subscription on the platform, each with its restaurant's name.
     *
     * <p>The restaurant id is resolved to a name here so the admin table never shows a bare id.
     */
    @Transactional(readOnly = true)
    public List<SubscriptionDtos.AdminSubscriptionResponse> subscriptions() {
        Map<Long, String> namesById = restaurants.findAll().stream()
                .collect(Collectors.toMap(Restaurant::getId, Restaurant::getName, (a, b) -> a));
        return subscriptions.findAllByOrderByUpdatedAtDesc().stream()
                .map(s -> {
                    SubscriptionPlan plan = plans.findById(s.getPlanId()).orElse(null);
                    return new SubscriptionDtos.AdminSubscriptionResponse(
                            s.getId(), s.getRestaurantId(), namesById.get(s.getRestaurantId()),
                            plan == null ? null : plan.getCode(),
                            plan == null ? null : plan.getName(),
                            s.getStatus(), s.getBillingCycle(), s.getAmountPaise(),
                            SubscriptionService.ENTITLING.contains(s.getStatus()), s.getRenewsAt());
                })
                .toList();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /**
     * The subscription that currently governs a venue.
     *
     * <p>An entitling status wins; otherwise the newest row is shown so the admin still sees why a
     * venue has no entitlements rather than an empty cell.
     */
    private Subscription currentSubscription(Long restaurantId) {
        return subscriptions
                .findFirstByRestaurantIdAndStatusInOrderByCreatedAtDesc(
                        restaurantId, List.copyOf(SubscriptionService.ENTITLING))
                .or(() -> subscriptions.findByRestaurantIdOrderByCreatedAtDesc(restaurantId)
                        .stream().findFirst())
                .orElse(null);
    }

    private static String normaliseProfileStatus(String raw) {
        if (raw == null || raw.isBlank()) {
            throw ApiErrors.badRequest("STATUS_REQUIRED", "A status is required.");
        }
        String value = raw.trim().toUpperCase();
        if (!PROFILE_STATUSES.contains(value)) {
            throw ApiErrors.badRequest("UNKNOWN_STATUS",
                    "Status must be one of " + PROFILE_STATUSES + ".");
        }
        return value;
    }

    private String userIdOf(Long userId) {
        return userId == null ? null
                : users.findById(userId).map(User::getUserId).orElse(null);
    }
}