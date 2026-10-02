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

    @Query("""
            SELECT c FROM Campaign c
            WHERE (:status IS NULL OR c.status = :status)
              AND (:creatorId IS NULL OR c.creatorId = :creatorId)
              AND (:restaurantId IS NULL OR c.restaurantId = :restaurantId)
            """)
    Page<Campaign> search(@Param("status") CampaignStatus status,
                          @Param("creatorId") Long creatorId,
                          @Param("restaurantId") Long restaurantId,
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