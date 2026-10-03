package com.customertrace.service;

import java.time.Instant;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.customertrace.domain.Campaign;
import com.customertrace.domain.Creator;
import com.customertrace.domain.Notification;
import com.customertrace.domain.Restaurant;
import com.customertrace.domain.Review;
import com.customertrace.exception.ApiErrors;
import com.customertrace.repository.CampaignRepository;
import com.customertrace.repository.CreatorRepository;
import com.customertrace.repository.NotificationRepository;
import com.customertrace.repository.RestaurantCreatorAssignmentRepository;
import com.customertrace.repository.RestaurantRepository;
import com.customertrace.repository.ReviewRepository;
import com.customertrace.security.CurrentUser;
import com.customertrace.web.dto.ReviewDtos;

/**
 * Creator reviews of restaurants.
 *
 * <p>The existing model records one review per (creator, restaurant, campaign), so a creator
 * cannot stack reviews on the same campaign; a review of a second campaign is a separate row.
 *
 * <p><b>Eligibility.</b> A creator may only review a restaurant they hold an active assignment
 * with. That check is what stops an arbitrary creator from reviewing any restaurant, and it is
 * re-read from the database rather than trusted from the request.
 *
 * <p>No review or rating is ever invented here — every returned row is a persisted record.
 */
@Service
public class ReviewService {

    /** Ratings are on a 1..5 scale. */
    static final int MIN_RATING = 1;
    static final int MAX_RATING = 5;

    private final ReviewRepository reviews;
    private final CreatorRepository creators;
    private final RestaurantRepository restaurants;
    private final CampaignRepository campaigns;
    private final RestaurantCreatorAssignmentRepository assignments;
    private final NotificationRepository notifications;

    public ReviewService(ReviewRepository reviews, CreatorRepository creators,
                         RestaurantRepository restaurants, CampaignRepository campaigns,
                         RestaurantCreatorAssignmentRepository assignments,
                         NotificationRepository notifications) {
        this.reviews = reviews;
        this.creators = creators;
        this.restaurants = restaurants;
        this.campaigns = campaigns;
        this.assignments = assignments;
        this.notifications = notifications;
    }

    // ── Reads ───────────────────────────────────────────────────────────────

    /** Reviews written by the signed-in creator. */
    @Transactional(readOnly = true)
    public List<ReviewDtos.ReviewResponse> byCreator(CurrentUser actor) {
        Creator creator = ownCreator(actor);
        return reviews.findByCreatorId(creator.getId()).stream().map(this::toResponse).toList();
    }

    /** Reviews for the signed-in restaurant, newest first. */
    @Transactional(readOnly = true)
    public List<ReviewDtos.ReviewResponse> forOwnRestaurant(CurrentUser actor) {
        Restaurant restaurant = ownRestaurant(actor);
        return reviews.findByRestaurantId(restaurant.getId()).stream()
                .map(this::toResponse).toList();
    }

    /**
     * Public profile view for one restaurant.
     *
     * <p>Returns only aggregate figures derived from real rows; a restaurant with no reviews
     * reports a zero average and no review list rather than an invented placeholder.
     */
    @Transactional(readOnly = true)
    public ReviewDtos.RestaurantReviewSummary summary(Long restaurantId) {
        if (!restaurants.existsById(restaurantId)) {
            throw ApiErrors.notFound("Restaurant " + restaurantId + " was not found.");
        }
        List<Review> rows = reviews.findByRestaurantId(restaurantId);
        if (rows.isEmpty()) {
            return new ReviewDtos.RestaurantReviewSummary(restaurantId, 0d, 0, List.of());
        }
        double sum = rows.stream().mapToInt(r -> r.getRating()).sum();
        double average = Math.round((sum * 100d) / rows.size()) / 100d;
        return new ReviewDtos.RestaurantReviewSummary(restaurantId, average, rows.size(),
                rows.stream().map(this::toResponse).toList());
    }

    /** The signed-in creator's review of one restaurant, or null when there is none. */
    @Transactional(readOnly = true)
    public ReviewDtos.ReviewResponse ownReviewOf(CurrentUser actor, Long restaurantId) {
        Creator creator = ownCreator(actor);
        return reviews.findFirstByCreatorIdAndRestaurantIdOrderByCreatedAtDesc(
                        creator.getId(), restaurantId)
                .map(this::toResponse).orElse(null);
    }

    // ── Writes ──────────────────────────────────────────────────────────────

    /**
     * Records a creator's review of a restaurant.
     *
     * <p>Order matters: eligibility is checked before the duplicate check so an ineligible caller
     * cannot learn whether a review already exists. Everything is inside one transaction, so a
     * rejected review leaves no trace.
     */
    @Transactional
    public ReviewDtos.ReviewResponse create(CurrentUser actor, ReviewDtos.CreateReviewRequest req) {
        Creator creator = ownCreator(actor);

        if (req.rating() < MIN_RATING || req.rating() > MAX_RATING) {
            throw ApiErrors.badRequest("INVALID_RATING",
                    "Rating must be between " + MIN_RATING + " and " + MAX_RATING + ".");
        }
        if (req.body() == null || req.body().isBlank()) {
            throw ApiErrors.badRequest("BODY_REQUIRED", "A review must include some text.");
        }
        if (req.body().length() > 1000) {
            throw ApiErrors.badRequest("BODY_TOO_LONG",
                    "A review may be at most 1000 characters.");
        }

        Restaurant restaurant = restaurants.findById(req.restaurantId())
                .orElseThrow(() -> ApiErrors.notFound(
                        "Restaurant " + req.restaurantId() + " was not found."));

        // Eligibility: an active assignment must exist, re-read from the database.
        if (!assignments.findActiveCreatorIds(restaurant.getId()).contains(creator.getId())) {
            throw ApiErrors.forbidden("You are not assigned to this restaurant, so you cannot "
                    + "review it.");
        }

        Long campaignId = resolveCampaign(creator, restaurant, req.campaignId());

        // One review per creator/restaurant/campaign.
        if (campaignId != null && reviews.findByCreatorIdAndRestaurantIdAndCampaignId(
                creator.getId(), restaurant.getId(), campaignId).isPresent()) {
            throw ApiErrors.conflict("DUPLICATE_REVIEW",
                    "You have already reviewed this restaurant for that campaign.");
        }

        Review review = new Review();
        review.setCreatorId(creator.getId());
        review.setRestaurantId(restaurant.getId());
        review.setCampaignId(campaignId);
        review.setRating(req.rating());
        review.setBody(req.body().trim());
        review.setCreatedAt(Instant.now());
        review.setUpdatedAt(Instant.now());
        reviews.save(review);

        notifyRestaurant(restaurant, "New review received",
                creator.getDisplayName() + " left a " + req.rating() + "-star review.");
        return toResponse(review);
    }

    /** The creator may update their own review rather than leaving a second one. */
    @Transactional
    public ReviewDtos.ReviewResponse update(CurrentUser actor, Long reviewId, int rating,
                                           String body) {
        Creator creator = ownCreator(actor);
        Review review = reviews.findById(reviewId).orElseThrow(
                () -> ApiErrors.notFound("Review " + reviewId + " was not found."));
        if (!review.getCreatorId().equals(creator.getId())) {
            throw ApiErrors.notFound("Review " + reviewId + " was not found.");
        }
        if (rating < MIN_RATING || rating > MAX_RATING) {
            throw ApiErrors.badRequest("INVALID_RATING",
                    "Rating must be between " + MIN_RATING + " and " + MAX_RATING + ".");
        }
        if (body == null || body.isBlank() || body.length() > 1000) {
            throw ApiErrors.badRequest("INVALID_BODY",
                    "A review must include text of at most 1000 characters.");
        }

        review.setRating(rating);
        review.setBody(body.trim());
        review.setUpdatedAt(Instant.now());
        reviews.save(review);
        return toResponse(review);
    }

    /**
     * Validates the campaign reference.
     *
     * <p>A campaign is optional, but when given it must be the creator's own and belong to the
     * restaurant being reviewed — a caller cannot attach a review to someone else's campaign.
     */
    private Long resolveCampaign(Creator creator, Restaurant restaurant, Long campaignId) {
        if (campaignId == null) {
            return null;
        }
        Campaign campaign = campaigns.findById(campaignId).orElseThrow(
                () -> ApiErrors.notFound("Campaign " + campaignId + " was not found."));
        if (!campaign.getCreatorId().equals(creator.getId())) {
            throw ApiErrors.forbidden("That campaign does not belong to you.");
        }
        if (!campaign.getRestaurantId().equals(restaurant.getId())) {
            throw ApiErrors.badRequest("CAMPAIGN_RESTAURANT_MISMATCH",
                    "That campaign belongs to a different restaurant.");
        }
        return campaign.getId();
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private Creator ownCreator(CurrentUser actor) {
        return creators.findByUserId(actor.userId()).orElseThrow(
                () -> ApiErrors.forbidden("This endpoint requires a creator account."));
    }

    private Restaurant ownRestaurant(CurrentUser actor) {
        return restaurants.findByUserId(actor.userId()).orElseThrow(
                () -> ApiErrors.forbidden("This endpoint requires a restaurant account."));
    }

    private void notifyRestaurant(Restaurant restaurant, String title, String body) {
        Notification n = new Notification();
        n.setType("REVIEW");
        n.setAudience("RESTAURANT");
        n.setRecipientId(restaurant.getUserId());
        n.setTitle(title);
        n.setBody(body);
        n.setLink("/restaurant/reviews");
        n.setCreatedAt(Instant.now());
        notifications.save(n);
    }

    private ReviewDtos.ReviewResponse toResponse(Review r) {
        String creatorName = creators.findById(r.getCreatorId())
                .map(Creator::getDisplayName).orElse(null);
        String restaurantName = restaurants.findById(r.getRestaurantId())
                .map(Restaurant::getName).orElse(null);
        return new ReviewDtos.ReviewResponse(r.getId(), r.getCreatorId(), creatorName,
                r.getRestaurantId(), restaurantName, r.getCampaignId(), r.getRating(),
                r.getBody(), r.getCreatedAt(), r.getUpdatedAt());
    }
}