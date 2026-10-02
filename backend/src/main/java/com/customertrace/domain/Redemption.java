package com.customertrace.domain;

import java.time.Instant;
import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A verified customer visit, explicitly confirmed by restaurant staff at bill settlement.
 *
 * <p>"Verified customers" are counted as {@code COUNT(DISTINCT customer_ref)}; a NULL
 * {@code customerRef} records an anonymous visit and is excluded from that metric while still
 * counting as a redemption.
 *
 * <p>Two database constraints close the concurrency holes that would otherwise let a client
 * overshoot a limit: {@code uk_redemption_scan} caps a scan at one redemption, and
 * {@code uk_redemption_daily} blocks the same customer redeeming the same campaign twice in
 * one day.
 */
@Entity
@Table(name = "redemptions")
public class Redemption {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "campaign_id", nullable = false)
    private Long campaignId;

    @Column(name = "restaurant_id", nullable = false)
    private Long restaurantId;

    @Column(name = "creator_id", nullable = false)
    private Long creatorId;

    @Column(name = "promo_code_id", nullable = false)
    private Long promoCodeId;

    @Column(name = "promo_code", nullable = false, length = 7)
    private String promoCode;

    /** Null for a walk-in that never scanned. */
    @Column(name = "scan_event_id")
    private Long scanEventId;

    /** Anonymous, non-identifying handle. Null means an unidentified customer. */
    @Column(name = "customer_ref", length = 80)
    private String customerRef;

    @Column(name = "visit_date", nullable = false)
    private LocalDate visitDate;

    @Column(name = "bill_paise", nullable = false)
    private long billPaise;

    @Column(name = "discount_paise", nullable = false)
    private long discountPaise;

    @Column(name = "channel", nullable = false, length = 16)
    private String channel = "DINE_IN";

    @Column(name = "table_ref", length = 40)
    private String tableRef;

    @Column(name = "scan_redeemed", nullable = false)
    private boolean scanRedeemed = false;

    /** The authorised staff member who confirmed the visit. */
    @Column(name = "redeemed_by", nullable = false)
    private Long redeemedBy;

    @Column(name = "staff_name", length = 120)
    private String staffName;

    @Column(name = "redeemed_at", nullable = false)
    private Instant redeemedAt = Instant.now();

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    /** True when this redemption identifies a specific (non-anonymous) customer. */
    public boolean hasCustomerRef() {
        return customerRef != null && !customerRef.isBlank();
    }
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

    public Long getCreatorId() {
        return creatorId;
    }

    public void setCreatorId(Long creatorId) {
        this.creatorId = creatorId;
    }

    public Long getPromoCodeId() {
        return promoCodeId;
    }

    public void setPromoCodeId(Long promoCodeId) {
        this.promoCodeId = promoCodeId;
    }

    public String getPromoCode() {
        return promoCode;
    }

    public void setPromoCode(String promoCode) {
        this.promoCode = promoCode;
    }

    public Long getScanEventId() {
        return scanEventId;
    }

    public void setScanEventId(Long scanEventId) {
        this.scanEventId = scanEventId;
    }

    public String getCustomerRef() {
        return customerRef;
    }

    public void setCustomerRef(String customerRef) {
        this.customerRef = customerRef;
    }

    public LocalDate getVisitDate() {
        return visitDate;
    }

    public void setVisitDate(LocalDate visitDate) {
        this.visitDate = visitDate;
    }

    public long getBillPaise() {
        return billPaise;
    }

    public void setBillPaise(long billPaise) {
        this.billPaise = billPaise;
    }

    public long getDiscountPaise() {
        return discountPaise;
    }

    public void setDiscountPaise(long discountPaise) {
        this.discountPaise = discountPaise;
    }

    public String getChannel() {
        return channel;
    }

    public void setChannel(String channel) {
        this.channel = channel;
    }

    public String getTableRef() {
        return tableRef;
    }

    public void setTableRef(String tableRef) {
        this.tableRef = tableRef;
    }

    public boolean isScanRedeemed() {
        return scanRedeemed;
    }

    public void setScanRedeemed(boolean scanRedeemed) {
        this.scanRedeemed = scanRedeemed;
    }

    public Long getRedeemedBy() {
        return redeemedBy;
    }

    public void setRedeemedBy(Long redeemedBy) {
        this.redeemedBy = redeemedBy;
    }

    public String getStaffName() {
        return staffName;
    }

    public void setStaffName(String staffName) {
        this.staffName = staffName;
    }

    public Instant getRedeemedAt() {
        return redeemedAt;
    }

    public void setRedeemedAt(Instant redeemedAt) {
        this.redeemedAt = redeemedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}