package com.customertrace.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.customertrace.domain.Booking;
import com.customertrace.domain.BookingStatus;

public interface BookingRepository extends JpaRepository<Booking, Long> {

    @Query("""
            SELECT b FROM Booking b
            WHERE (:creatorId IS NULL OR b.creatorId = :creatorId)
              AND (:restaurantId IS NULL OR b.restaurantId = :restaurantId)
              AND (:status IS NULL OR b.status = :status)
            """)
    Page<Booking> search(@Param("creatorId") Long creatorId,
                         @Param("restaurantId") Long restaurantId,
                         @Param("status") BookingStatus status,
                         Pageable pageable);

    List<Booking> findByRestaurantIdOrderByVisitDateDesc(Long restaurantId);
}