package com.customertrace.repository;

import java.time.LocalDate;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.customertrace.domain.ScanEvent;

/**
 * Scan-event persistence.
 *
 * <p>Scans are counted separately from redemptions. {@code valid = true} alone forms the
 * analytics funnel; a scan never implies a verified customer.
 */
public interface ScanEventRepository extends JpaRepository<ScanEvent, Long> {

    @Query("SELECT COUNT(s) FROM ScanEvent s WHERE s.campaignId = :campaignId AND s.valid = true")
    long countValidScans(@Param("campaignId") Long campaignId);

    @Query("SELECT COUNT(s) FROM ScanEvent s "
            + "WHERE s.campaignId = :campaignId AND s.valid = true AND s.method = :method")
    long countValidScansByMethod(@Param("campaignId") Long campaignId, @Param("method") String method);

    @Query("SELECT COUNT(s) FROM ScanEvent s WHERE s.campaignId = :campaignId AND s.valid = false")
    long countInvalidScans(@Param("campaignId") Long campaignId);

    @Query("SELECT s FROM ScanEvent s WHERE s.campaignId = :campaignId ORDER BY s.scannedAt DESC")
    List<ScanEvent> findRecentForCampaign(@Param("campaignId") Long campaignId);
}