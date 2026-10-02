package com.customertrace.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.customertrace.domain.RestaurantCreatorAssignment;

public interface RestaurantCreatorAssignmentRepository
        extends JpaRepository<RestaurantCreatorAssignment, Long> {

    Optional<RestaurantCreatorAssignment> findByRestaurantIdAndCreatorId(Long restaurantId,
                                                                        Long creatorId);

    List<RestaurantCreatorAssignment> findByRestaurantIdOrderByCreatedAtDesc(Long restaurantId);

    List<RestaurantCreatorAssignment> findByCreatorIdOrderByCreatedAtDesc(Long creatorId);

    /** Creator ids assigned to a restaurant — scopes restaurant-side creator discovery. */
    @Query("SELECT a.creatorId FROM RestaurantCreatorAssignment a "
            + "WHERE a.restaurantId = :restaurantId AND a.active = true")
    List<Long> findActiveCreatorIds(@Param("restaurantId") Long restaurantId);
}