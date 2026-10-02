package com.customertrace.repository;

import java.time.LocalDate;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.customertrace.domain.Redemption;

public interface RedemptionRepository extends JpaRepository<Redemption, Long> {

    /**
     * Counts <em>distinct</em> customers for a campaign.
     *
     * <p>Rows with a NULL {@code customer_ref} are anonymous walk-ins and are excluded, so this
     * is the "verified customers" metric — never the redemption count.
     */
    @Query("SELECT COUNT(DISTINCT r.customerRef) FROM Redemption r "
            + "WHERE r.campaignId = :campaignId AND r.customerRef IS NOT NULL")
    long countDistinctCustomers(@Param("campaignId") Long campaignId);

    @Query("SELECT COUNT(r) FROM Redemption r WHERE r.campaignId = :campaignId")
    long countByCampaign(@Param("campaignId") Long campaignId);

    @Query("SELECT COALESCE(SUM(r.discountPaise), 0) FROM Redemption r WHERE r.campaignId = :campaignId")
    long sumDiscountPaise(@Param("campaignId") Long campaignId);

    @Query("SELECT COALESCE(SUM(r.billPaise), 0) FROM Redemption r WHERE r.campaignId = :campaignId")
    long sumBillPaise(@Param("campaignId") Long campaignId);

    /** True when this customer already redeemed this campaign on this date. */
    boolean existsByCampaignIdAndCustomerRefAndVisitDate(Long campaignId, String customerRef,
                                                          LocalDate visitDate);

    boolean existsByScanEventId(Long scanEventId);

    @Query("SELECT r FROM Redemption r WHERE r.campaignId = :campaignId "
            + "ORDER BY r.redeemedAt DESC")
    List<Redemption> findRecentForCampaign(@Param("campaignId") Long campaignId);
}