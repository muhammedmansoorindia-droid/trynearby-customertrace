package com.customertrace.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A scan event: intent that a promo code was presented.
 *
 * <p>Critically, a scan is NOT a verified customer and never counts toward the customer cap.
 * Invalid scans are retained with {@code valid = false} so misuse stays auditable without
 * polluting the analytics funnel. Verified visits live in {@link Redemption}.
 */
@Entity
@Table(name = "scan_events")
public class ScanEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Nullable since V3: a rejected scan has no resolvable campaign, and recording the attempt is
     * exactly what makes misuse auditable.
     */
    @Column(name = "campaign_id")
    private Long campaignId;

    @Column(name = "restaurant_id", nullable = false)
    private Long restaurantId;

    /** QR or MANUAL — reported separately so channel performance is comparable. */
    @Column(name = "method", nullable = false, length = 16)
    private String method;

    @Column(name = "promo_code", nullable = false, columnDefinition = "CHAR(7)")
    private String promoCode;

    @Column(name = "valid", nullable = false)
    private boolean valid = true;

    @Column(name = "rejection_reason", length = 64)
    private String rejectionReason;

    /** Optional, non-identifying device label. No customer PII is collected. */
    @Column(name = "device_label", length = 120)
    private String deviceLabel;

    @Column(name = "scanned_by")
    private Long scannedBy;

    @Column(name = "scanned_at", nullable = false)
    private Instant scannedAt = Instant.now();

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getCampaignId() {
        return campaignId;
    }

    public void setCampaignId(Long campaignId) {
        this.campaignId = campaignId;
    }

    public Long getRestaurantId() {
        return restaurantId;
    }

    public void setRestaurantId(Long restaurantId) {
        this.restaurantId = restaurantId;
    }

    public String getMethod() {
        return method;
    }

    public void setMethod(String method) {
        this.method = method;
    }

    public String getPromoCode() {
        return promoCode;
    }

    public void setPromoCode(String promoCode) {
        this.promoCode = promoCode;
    }

    public boolean isValid() {
        return valid;
    }

    public void setValid(boolean valid) {
        this.valid = valid;
    }

    public String getRejectionReason() {
        return rejectionReason;
    }

    public void setRejectionReason(String rejectionReason) {
        this.rejectionReason = rejectionReason;
    }

    public String getDeviceLabel() {
        return deviceLabel;
    }

    public void setDeviceLabel(String deviceLabel) {
        this.deviceLabel = deviceLabel;
    }

    public Long getScannedBy() {
        return scannedBy;
    }

    public void setScannedBy(Long scannedBy) {
        this.scannedBy = scannedBy;
    }

    public Instant getScannedAt() {
        return scannedAt;
    }

    public void setScannedAt(Instant scannedAt) {
        this.scannedAt = scannedAt;
    }
}