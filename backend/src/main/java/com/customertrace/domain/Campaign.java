package com.customertrace.domain;

import java.time.Instant;
import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * A promotional campaign: the offer itself plus its approval and tracking lifecycle.
 *
 * <p>Distinct from a {@link Booking}, which is a request to collaborate. A campaign becomes
 * {@link CampaignStatus#ACTIVE} only once BOTH the admin and the restaurant have approved it;
 * a single rejection moves it to {@code REJECTED}. The rule lives in
 * {@code CampaignWorkflowService} rather than in a trigger so it is unit-testable.
 *
 * <p>{@code version} provides optimistic locking so two approvers cannot silently overwrite
 * one another's decision.
 */
@Entity
@Table(name = "campaigns")
public class Campaign {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "creator_id", nullable = false)
    private Long creatorId;

    @Column(name = "restaurant_id", nullable = false)
    private Long restaurantId;

    @Column(name = "title", nullable = false, length = 160)
    private String title;

    @Column(name = "description", length = 1000)
    private String description;

    @Column(name = "offer_text", nullable = false, length = 255)
    private String offerText;

    @Column(name = "discount_percent", nullable = false)
    private int discountPercent;

    /** Minimum bill in paise. Null means no minimum. */
    @Column(name = "min_bill_paise")
    private Long minBillPaise;

    /** Customer cap. Constrained to 10–99 because it is encoded as the promo code's NN. */
    @Column(name = "target_customers", nullable = false)
    private int targetCustomers;

    /** Campaign length in days. Constrained to 1–31 because it is the promo code's DD. */
    @Column(name = "duration_days", nullable = false)
    private int durationDays;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private CampaignStatus status = CampaignStatus.DRAFT;

    @Enumerated(EnumType.STRING)
    @Column(name = "admin_decision", length = 16)
    private Decision adminDecision;

    @Enumerated(EnumType.STRING)
    @Column(name = "restaurant_decision", length = 16)
    private Decision restaurantDecision;

    @Column(name = "submitted_at")
    private Instant submittedAt;

    @Column(name = "activated_at")
    private Instant activatedAt;

    /** How many times a rejected campaign has been explicitly resubmitted. */
    @Column(name = "resubmission_count", nullable = false)
    private int resubmissionCount = 0;

    @Version
    @Column(name = "version", nullable = false)
    private Long version = 0L;
@Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    /** True only when both parties have approved — the sole precondition for redemption. */
    public boolean isFullyApproved() {
        return adminDecision == Decision.APPROVED && restaurantDecision == Decision.APPROVED;
    }

    /** True when either party has rejected, which permanently blocks activation. */
    public boolean isRejectedByEitherParty() {
        return adminDecision == Decision.REJECTED || restaurantDecision == Decision.REJECTED;
    }

    /** True when the validity window contains {@code date}. */
    public boolean isWithinValidity(LocalDate date) {
        return !date.isBefore(startDate) && !date.isAfter(endDate);
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getCreatorId() {
        return creatorId;
    }

    public void setCreatorId(Long creatorId) {
        this.creatorId = creatorId;
    }

    public Long getRestaurantId() {
        return restaurantId;
    }

    public void setRestaurantId(Long restaurantId) {
        this.restaurantId = restaurantId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getOfferText() {
        return offerText;
    }

    public void setOfferText(String offerText) {
        this.offerText = offerText;
    }

    public int getDiscountPercent() {
        return discountPercent;
    }

    public void setDiscountPercent(int discountPercent) {
        this.discountPercent = discountPercent;
    }

    public Long getMinBillPaise() {
        return minBillPaise;
    }

    public void setMinBillPaise(Long minBillPaise) {
        this.minBillPaise = minBillPaise;
    }

    public int getTargetCustomers() {
        return targetCustomers;
    }

    public void setTargetCustomers(int targetCustomers) {
        this.targetCustomers = targetCustomers;
    }

    public int getDurationDays() {
        return durationDays;
    }

    public void setDurationDays(int durationDays) {
        this.durationDays = durationDays;
    }

    public LocalDate getStartDate() {
        return startDate;
    }

    public void setStartDate(LocalDate startDate) {
        this.startDate = startDate;
    }

    public LocalDate getEndDate() {
        return endDate;
    }

    public void setEndDate(LocalDate endDate) {
        this.endDate = endDate;
    }

    public CampaignStatus getStatus() {
        return status;
    }

    public void setStatus(CampaignStatus status) {
        this.status = status;
    }

    public Decision getAdminDecision() {
        return adminDecision;
    }

    public void setAdminDecision(Decision adminDecision) {
        this.adminDecision = adminDecision;
    }

    public Decision getRestaurantDecision() {
        return restaurantDecision;
    }

    public void setRestaurantDecision(Decision restaurantDecision) {
        this.restaurantDecision = restaurantDecision;
    }

    public Instant getSubmittedAt() {
        return submittedAt;
    }

    public void setSubmittedAt(Instant submittedAt) {
        this.submittedAt = submittedAt;
    }

    public Instant getActivatedAt() {
        return activatedAt;
    }

    public void setActivatedAt(Instant activatedAt) {
        this.activatedAt = activatedAt;
    }

    public int getResubmissionCount() {
        return resubmissionCount;
    }

    public void setResubmissionCount(int resubmissionCount) {
        this.resubmissionCount = resubmissionCount;
    }

    public Long getVersion() {
        return version;
    }

    public void setVersion(Long version) {
        this.version = version;
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
