package com.customertrace.repository;

import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.customertrace.domain.Creator;

public interface CreatorRepository extends JpaRepository<Creator, Long> {

    Optional<Creator> findByUserId(Long userId);

    Optional<Creator> findByHandleIgnoreCase(String handle);

    boolean existsByHandleIgnoreCase(String handle);

    /**
     * Creator discovery for restaurants and admins.
     *
     * <p>Backed by the city/followers/status indexes. Free text matches display name, handle
     * and niche. Every parameter is bound by Spring Data, so no user input is concatenated
     * into SQL.
     */
    @Query("""
            SELECT c FROM Creator c
            WHERE c.status = 'ACTIVE'
              AND (:city IS NULL OR LOWER(c.city) = LOWER(:city))
              AND (:q IS NULL OR LOWER(c.displayName) LIKE LOWER(CONCAT('%', :q, '%'))
                                 OR LOWER(c.handle) LIKE LOWER(CONCAT('%', :q, '%'))
                                 OR LOWER(c.niche) LIKE LOWER(CONCAT('%', :q, '%')))
              AND (:minFollowers IS NULL OR c.followers >= :minFollowers)
            """)
    Page<Creator> search(@Param("q") String q,
                        @Param("city") String city,
                        @Param("minFollowers") Integer minFollowers,
                        Pageable pageable);
}