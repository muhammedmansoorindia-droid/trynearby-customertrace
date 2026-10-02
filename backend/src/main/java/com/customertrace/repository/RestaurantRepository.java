package com.customertrace.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.customertrace.domain.Restaurant;

public interface RestaurantRepository extends JpaRepository<Restaurant, Long> {

    Optional<Restaurant> findByUserId(Long userId);

    Optional<Restaurant> findBySlug(String slug);

    boolean existsBySlug(String slug);

    @Query("""
            SELECT r FROM Restaurant r
            WHERE r.status = 'ACTIVE'
              AND (:city IS NULL OR LOWER(r.city) = LOWER(:city))
              AND (:q IS NULL OR LOWER(r.name) LIKE LOWER(CONCAT('%', :q, '%'))
                                 OR LOWER(r.cuisine) LIKE LOWER(CONCAT('%', :q, '%')))
            """)
    Page<Restaurant> search(@Param("q") String q, @Param("city") String city, Pageable pageable);

    @Query("SELECT r.city FROM Restaurant r WHERE r.status = 'ACTIVE' ORDER BY r.city")
    List<String> findDistinctCities();
}