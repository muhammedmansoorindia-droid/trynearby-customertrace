package com.customertrace.repository;

import java.time.LocalDate;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.customertrace.domain.Campaign;

/**
 * Aggregate queries for the reports and dashboards.
 *
 * <p>Counts come from the database rather than being assembled in Java, so a dashboard never
 * fabricates or re-derives a figure inconsistently. Scans and redemptions stay separate: a scan
 * is interest, and only a distinct {@code customer_ref} is a verified customer.
 *
 * <p>Scoping parameters are nullable; {@code null} means "no tenant filter" and is used only by
 * the administrator view, which is authorised by role at the service layer.
 */
public interface CampaignReportRepository extends JpaRepository<Campaign, Long> {

    @Query(value = "SELECT COUNT(*) FROM campaigns WHERE status = :status", nativeQuery = true)
    long countByStatus(@Param("status") String status);

    @Query(value = "SELECT COUNT(*) FROM campaigns", nativeQuery = true)
    long countAll();

    @Query(value = """
            SELECT COUNT(*) FROM scan_events
            WHERE campaign_id = :campaignId AND valid = 1
              AND scanned_at >= :from AND scanned_at < :to
            """, nativeQuery = true)
    long countValidScans(@Param("campaignId") Long campaignId,
                         @Param("from") java.time.Instant from,
                         @Param("to") java.time.Instant to);

    @Query(value = """
            SELECT COUNT(*) FROM scan_events
            WHERE valid = 1 AND scanned_at >= :from AND scanned_at < :to
              AND (:restaurantId IS NULL OR restaurant_id = :restaurantId)
              AND (:creatorId IS NULL OR campaign_id IN
                   (SELECT id FROM campaigns WHERE creator_id = :creatorId))
            """, nativeQuery = true)
    long countValidScansScoped(@Param("restaurantId") Long restaurantId,
                               @Param("creatorId") Long creatorId,
                               @Param("from") java.time.Instant from,
                               @Param("to") java.time.Instant to);

    @Query(value = """
            SELECT COUNT(*) FROM redemptions
            WHERE campaign_id = :campaignId AND redeemed_at >= :from AND redeemed_at < :to
            """, nativeQuery = true)
    long countRedemptions(@Param("campaignId") Long campaignId,
                          @Param("from") java.time.Instant from,
                          @Param("to") java.time.Instant to);

    /** Distinct customers: a repeat visit is one customer, not two. */
    @Query(value = """
            SELECT COUNT(DISTINCT customer_ref) FROM redemptions
            WHERE campaign_id = :campaignId AND customer_ref IS NOT NULL
              AND redeemed_at >= :from AND redeemed_at < :to
            """, nativeQuery = true)
    long countVerifiedCustomers(@Param("campaignId") Long campaignId,
                                @Param("from") java.time.Instant from,
                                @Param("to") java.time.Instant to);

    @Query(value = """
            SELECT COUNT(*) FROM redemptions
            WHERE redeemed_at >= :from AND redeemed_at < :to
              AND (:restaurantId IS NULL OR restaurant_id = :restaurantId)
              AND (:creatorId IS NULL OR creator_id = :creatorId)
            """, nativeQuery = true)
    long countRedemptionsScoped(@Param("restaurantId") Long restaurantId,
                                @Param("creatorId") Long creatorId,
                                @Param("from") java.time.Instant from,
                                @Param("to") java.time.Instant to);

    @Query(value = """
            SELECT COUNT(DISTINCT customer_ref) FROM redemptions
            WHERE customer_ref IS NOT NULL AND redeemed_at >= :from AND redeemed_at < :to
              AND (:restaurantId IS NULL OR restaurant_id = :restaurantId)
              AND (:creatorId IS NULL OR creator_id = :creatorId)
            """, nativeQuery = true)
    long countVerifiedCustomersScoped(@Param("restaurantId") Long restaurantId,
                                      @Param("creatorId") Long creatorId,
                                      @Param("from") java.time.Instant from,
                                      @Param("to") java.time.Instant to);

    @Query(value = """
            SELECT COALESCE(SUM(discount_paise), 0) FROM redemptions
            WHERE redeemed_at >= :from AND redeemed_at < :to
              AND (:restaurantId IS NULL OR restaurant_id = :restaurantId)
              AND (:creatorId IS NULL OR creator_id = :creatorId)
            """, nativeQuery = true)
    long sumDiscountScoped(@Param("restaurantId") Long restaurantId,
                           @Param("creatorId") Long creatorId,
                           @Param("from") java.time.Instant from,
                           @Param("to") java.time.Instant to);
/**
     * Daily scan trend. Returns one row per day that has data, so an empty range yields an empty
     * list rather than a fabricated zero series.
     */
    @Query(value = """
            SELECT DATE(scanned_at) AS day, COUNT(*) AS scans
            FROM scan_events
            WHERE valid = 1 AND scanned_at >= :from AND scanned_at < :to
              AND (:restaurantId IS NULL OR restaurant_id = :restaurantId)
              AND (:creatorId IS NULL OR campaign_id IN
                   (SELECT id FROM campaigns WHERE creator_id = :creatorId))
            GROUP BY DATE(scanned_at)
            ORDER BY day
            """, nativeQuery = true)
    List<DailyCount> dailyScans(@Param("restaurantId") Long restaurantId,
                                @Param("creatorId") Long creatorId,
                                @Param("from") java.time.Instant from,
                                @Param("to") java.time.Instant to);

    @Query(value = """
            SELECT DATE(redeemed_at) AS day, COUNT(DISTINCT customer_ref) AS redemptions
            FROM redemptions
            WHERE customer_ref IS NOT NULL AND redeemed_at >= :from AND redeemed_at < :to
              AND (:restaurantId IS NULL OR restaurant_id = :restaurantId)
              AND (:creatorId IS NULL OR creator_id = :creatorId)
            GROUP BY DATE(redeemed_at)
            ORDER BY day
            """, nativeQuery = true)
    List<DailyCount> dailyRedemptions(@Param("restaurantId") Long restaurantId,
                                      @Param("creatorId") Long creatorId,
                                      @Param("from") java.time.Instant from,
                                      @Param("to") java.time.Instant to);

    /** One day's activity: {@code day} plus whichever count alias the query selected. */
    interface DailyCount {
        LocalDate getDay();

        Long getScans();

        Long getRedemptions();
    }
}