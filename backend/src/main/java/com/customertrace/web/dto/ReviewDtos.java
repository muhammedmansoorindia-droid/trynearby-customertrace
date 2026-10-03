package com.customertrace.web.dto;

import java.time.Instant;
import java.util.List;

/** Review payloads. Ratings are on a 1..5 scale, matching the TINYINT column. */
public final class ReviewDtos {

    private ReviewDtos() {
    }

    /** One persisted review. */
    public record ReviewResponse(
            Long id,
            Long creatorId,
            String creatorName,
            Long restaurantId,
            String restaurantName,
            Long campaignId,
            int rating,
            String body,
            Instant createdAt,
            Instant updatedAt) {
    }

    /**
     * A restaurant's review profile.
     *
     * <p>{@code averageRating} is derived from real rows; a restaurant with no reviews reports 0.
     */
    public record RestaurantReviewSummary(
            Long restaurantId,
            double averageRating,
            int totalReviews,
            List<ReviewResponse> reviews) {
    }

    /** Body for creating a review. */
    public record CreateReviewRequest(Long restaurantId, Long campaignId, int rating,
                                      String body) {
    }

    /** Body for editing an existing review. */
    public record UpdateReviewRequest(int rating, String body) {
    }
}