package com.customertrace.web.dto;

import java.time.Instant;

/** Creator-to-restaurant assignment payloads. */
public final class AssignmentDtos {

    private AssignmentDtos() {
    }

    /** One assignment row, including who created it and when. */
    public record AssignmentResponse(
            Long id,
            Long restaurantId,
            Long creatorId,
            boolean active,
            Long assignedBy,
            Instant createdAt,
            Instant updatedAt,
            /**
             * Resolved display names, null when the profile has since been removed.
             *
             * <p>Carried on the row rather than looked up per screen, so a creator's wizard can
             * name the venue it is about to target instead of showing a bare id.
             */
            String restaurantName,
            String creatorName) {
    }

    /** Body for creating an assignment. */
    public record AssignRequest(Long restaurantId, Long creatorId) {
    }

    /**
     * One selectable creator or restaurant, for the admin pickers.
     *
     * <p>{@code subtitle} carries the disambiguating detail — a handle for a creator, a city for
     * a restaurant — so two similarly named entries can still be told apart.
     */
    /**
     * One restaurant as an administrator sees it.
     *
     * <p>Carries the profile and its status so the admin portal can manage both without a second
     * lookup, plus the subscription status that decides what the venue can actually do.
     */
    public record RestaurantSummary(
            Long id,
            String name,
            String city,
            String region,
            String status,
            int ratingCount,
            /** Mean rating out of five, or null when the venue has no ratings yet. */
            Double averageRating,
            String subscriptionPlan,
            String subscriptionStatus,
            int activeCreatorCount,
            long campaignCount,
            String userId) {
    }

    /** Body for an administrator changing a restaurant's or creator's profile status. */
    public record StatusRequest(String status) {
    }

    /** One creator as an administrator sees it. */
    public record CreatorSummary(
            Long id,
            String displayName,
            String handle,
            String city,
            String niche,
            int followers,
            boolean verified,
            String status,
            int activeRestaurantCount,
            long campaignCount,
            String userId) {
    }
}