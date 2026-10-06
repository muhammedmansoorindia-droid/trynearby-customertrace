package com.customertrace.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.customertrace.domain.Campaign;
import com.customertrace.domain.CampaignStatus;
import com.customertrace.domain.DecisionParty;

import jakarta.persistence.LockModeType;

public interface CampaignRepository extends JpaRepository<Campaign, Long> {

    /**
     * Campaigns matching an optional status, owner and free-text term, in a chosen order.
     *
     * <p>The term matches the campaign code, the creator's name or the restaurant's name — the three
     * things an administrator or a restaurant actually searches by. Both joins are {@code LEFT} so a
     * campaign whose profile has since been removed is still findable by its own code.
     *
     * <p>Ordering arrives through {@code Pageable} rather than being hard-coded, so the caller
     * chooses the direction and nothing has to re-sort in memory.
     */
    @Query("""
            SELECT c FROM Campaign c
            LEFT JOIN Creator cr ON cr.id = c.creatorId
            LEFT JOIN Restaurant r ON r.id = c.restaurantId
            WHERE (:status IS NULL OR c.status = :status)
              AND (:creatorId IS NULL OR c.creatorId = :creatorId)
              AND (:restaurantId IS NULL OR c.restaurantId = :restaurantId)
              AND (:term IS NULL
                   OR LOWER(c.title) LIKE LOWER(CONCAT('%', :term, '%'))
                   OR LOWER(COALESCE(cr.displayName, '')) LIKE LOWER(CONCAT('%', :term, '%'))
                   OR LOWER(COALESCE(r.name, '')) LIKE LOWER(CONCAT('%', :term, '%')))
            """)
    Page<Campaign> search(@Param("status") CampaignStatus status,
                          @Param("creatorId") Long creatorId,
                          @Param("restaurantId") Long restaurantId,
                          @Param("term") String term,
                          Pageable pageable);

    @Query("SELECT c FROM Campaign c WHERE c.restaurantId = :restaurantId AND c.status = :status")
    List<Campaign> findByRestaurantAndStatus(@Param("restaurantId") Long restaurantId,
                                             @Param("status") CampaignStatus status);

    /**
     * Re-reads the campaign under a pessimistic write lock.
     *
     * <p>Used by the approval workflow so two concurrent approvers cannot interleave: the
     * second caller blocks until the first transaction commits and then re-evaluates the
     * (now changed) state instead of overwriting it.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM Campaign c WHERE c.id = :id")
    Optional<Campaign> findByIdForUpdate(@Param("id") Long id);

    boolean existsByCreatorIdAndStatus(Long creatorId, CampaignStatus status);
}