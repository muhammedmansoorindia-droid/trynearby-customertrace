package com.customertrace.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.customertrace.domain.Subscription;

public interface SubscriptionRepository extends JpaRepository<Subscription, Long> {

    /** The restaurant's subscriptions, newest first. */
    List<Subscription> findByRestaurantIdOrderByCreatedAtDesc(Long restaurantId);

    /** The subscription that currently grants entitlements, if any. */
    Optional<Subscription> findFirstByRestaurantIdAndStatusInOrderByCreatedAtDesc(
            Long restaurantId, List<String> statuses);

    /** Every subscription on the platform, most recently touched first, for administrators. */
    List<Subscription> findAllByOrderByUpdatedAtDesc();

    long countByStatus(String status);
}