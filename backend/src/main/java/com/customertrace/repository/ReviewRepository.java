package com.customertrace.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import com.customertrace.domain.Review;

public interface ReviewRepository extends JpaRepository<Review, Long> {

    Page<Review> findByRestaurantIdOrderByCreatedAtDesc(Long restaurantId, Pageable pageable);

    List<Review> findByRestaurantId(Long restaurantId);

    List<Review> findByCreatorId(Long creatorId);

    /** One review per creator/restaurant/campaign; used to block duplicates. */
    Optional<Review> findByCreatorIdAndRestaurantIdAndCampaignId(Long creatorId, Long restaurantId,
                                                                Long campaignId);
}