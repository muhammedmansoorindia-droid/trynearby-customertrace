package com.customertrace.web.dto;

import java.time.Instant;
import java.time.LocalDate;

import com.customertrace.domain.CampaignStatus;
import com.customertrace.domain.Decision;
import com.customertrace.domain.DecisionParty;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Campaign request/response payloads.
 *
 * <p>Create requests deliberately omit any status, decision or creator/restaurant id: those are
 * assigned by the server from the authenticated principal, so a client cannot create a campaign
 * that is already approved or attribute it to someone else.
 */
public final class CampaignDtos {

    private CampaignDtos() {
    }

    /**
     * Body for creating a campaign.
 *
 * <p>There is no {@code title}: the campaign is named by the server from these very settings, so
     * the name always states the real target, duration and discount. A client that still sends one
     * is ignored rather than trusted.
 */
    public record CreateCampaignRequest(
            @NotNull(message = "A restaurant must be selected")
            Long restaurantId,

            @Size(max = 1000, message = "Description must be at most 1000 characters")
            String description,

            @NotBlank(message = "An offer description is required")
            @Size(max = 255, message = "Offer must be at most 255 characters")
            String offerText,

            // Must land on 2-10 so it maps to exactly one discount letter (B-J). A creator cannot
            // pick an arbitrary percentage, because the code's last character has to state the
            // discount for staff reading the QR at the till.
            @Min(value = 2, message = "Discount must be between 2% and 10%")
            @Max(value = 10, message = "Discount must be between 2% and 10%")
            int discountPercent,

            @Min(value = 0, message = "Minimum bill cannot be negative")
            Long minBillPaise,

            // Bounded to 1-99 because the value is encoded as the promo code's two NN digits.
            @Min(value = 1, message = "Customer target must be at least 1")
            @Max(value = 99, message = "Customer target must be at most 99")
            int targetCustomers,

            @Min(value = 1, message = "Duration must be at least 1 day")
            @Max(value = 99, message = "Duration must be at most 99 days")
            int durationDays,

            @NotNull(message = "A start date is required")
            LocalDate startDate,

            @NotNull(message = "An end date is required")
            LocalDate endDate) {
    }

    /** A single party's approve/reject decision. */
    public record DecisionRequest(
            @NotNull(message = "A decision is required")
            Decision decision,

            @Size(max = 400, message = "Reason must be at most 400 characters")
            String reason) {
    }

    /** Read model for a campaign, including both decision states for the creator. */
    public record CampaignResponse(
            Long id,
            Long creatorId,
            String creatorName,
            Long restaurantId,
            String restaurantName,
            String title,
            String description,
            String offerText,
            int discountPercent,
            Long minBillPaise,
            int targetCustomers,
            int durationDays,
            LocalDate startDate,
            LocalDate endDate,
            CampaignStatus status,
            Decision adminDecision,
            Decision restaurantDecision,
            String adminReason,
            String restaurantReason,
            String promoCode,
            int resubmissionCount,
            boolean canSubmit,
            boolean canApprove,
            Instant submittedAt,
            Instant activatedAt,
            Instant createdAt) {
    }

    /** One entry in the campaign status history. */
    public record StatusHistoryResponse(
            Long id,
            CampaignStatus fromStatus,
            CampaignStatus toStatus,
            String reason,
            String changedByUserId,
            Instant changedAt) {
    }
}