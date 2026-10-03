package com.customertrace.web.controller;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.customertrace.security.CurrentUser;
import com.customertrace.service.ReviewService;
import com.customertrace.web.dto.ReviewDtos;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Creator reviews of restaurants.
 *
 * <p>Creation is creator-only; each side reads back its own reviews, and the restaurant-facing
 * summary is available to any signed-in user for profile display.
 */
@RestController
@RequestMapping("/api")
@Tag(name = "Reviews")
public class ReviewController {

    private final ReviewService reviews;

    public ReviewController(ReviewService reviews) {
        this.reviews = reviews;
    }

    @PostMapping("/creator/reviews")
    @Operation(summary = "Review an assigned restaurant")
    public ResponseEntity<ReviewDtos.ReviewResponse> create(
            @AuthenticationPrincipal CurrentUser actor,
            @RequestBody ReviewDtos.CreateReviewRequest request) {
        return ResponseEntity.status(201).body(reviews.create(actor, request));
    }

    @PutMapping("/creator/reviews/{id}")
    @Operation(summary = "Edit one of the creator's own reviews")
    public ResponseEntity<ReviewDtos.ReviewResponse> update(
            @AuthenticationPrincipal CurrentUser actor, @PathVariable Long id,
            @RequestBody ReviewDtos.UpdateReviewRequest request) {
        return ResponseEntity.ok(
                reviews.update(actor, id, request.rating(), request.body()));
    }

    @GetMapping("/creator/reviews")
    @Operation(summary = "Reviews written by the signed-in creator")
    public ResponseEntity<List<ReviewDtos.ReviewResponse>> byCreator(
            @AuthenticationPrincipal CurrentUser actor) {
        return ResponseEntity.ok(reviews.byCreator(actor));
    }

    @GetMapping("/creator/reviews/restaurant/{restaurantId}")
    @Operation(summary = "The creator's own review of one restaurant, if any")
    public ResponseEntity<ReviewDtos.ReviewResponse> ownReview(
            @AuthenticationPrincipal CurrentUser actor, @PathVariable Long restaurantId) {
        ReviewDtos.ReviewResponse response = reviews.ownReviewOf(actor, restaurantId);
        // No review yet is an empty 200, so the client can render without special-casing 404.
        return ResponseEntity.ok(response);
    }

    @GetMapping("/restaurant/reviews")
    @Operation(summary = "Reviews for the signed-in restaurant")
    public ResponseEntity<List<ReviewDtos.ReviewResponse>> forOwnRestaurant(
            @AuthenticationPrincipal CurrentUser actor) {
        return ResponseEntity.ok(reviews.forOwnRestaurant(actor));
    }

    @GetMapping("/restaurants/{restaurantId}/reviews")
    @Operation(summary = "Review profile for a restaurant, including its average rating")
    public ResponseEntity<ReviewDtos.RestaurantReviewSummary> summary(
            @PathVariable Long restaurantId) {
        return ResponseEntity.ok(reviews.summary(restaurantId));
    }
}