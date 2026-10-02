package com.customertrace.web.dto;

import java.time.LocalDate;

import com.customertrace.domain.CampaignStatus;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Scan, lookup and redemption payloads.
 *
 * <p>Amounts are paise (integers) so no rounding drift enters the ledger.
 */
public final class RedemptionDtos {

    private RedemptionDtos() {
    }

    /** Records that a code was presented. Deliberately creates no customer record. */
    public record ScanRequest(
            @NotBlank(message = "A promo code is required")
            @Size(max = 7, message = "A promo code is 7 characters")
            String promoCode,

            @Pattern(regexp = "QR|MANUAL", message = "Method must be QR or MANUAL")
            String method,

            @Size(max = 120, message = "Device label must be at most 120 characters")
            String deviceLabel) {
    }

    /** Manual promo lookup result, shown to staff before they confirm anything. */
    public record LookupResponse(
            String promoCode,
            String campaignTitle,
            String offerText,
            String restaurantName,
            Long restaurantId,
            Long campaignId,
            CampaignStatus campaignStatus,
            String validity,
            String limitStatus,
            long redemptions,
            long verifiedCustomers,
            int targetCustomers,
            boolean exclusiveTier,
            String tierLetter) {
    }

    /** Result of recording a scan. A scan never changes {@code verifiedCustomers}. */
    public record ScanResponse(
            Long scanId,
            String promoCode,
            boolean valid,
            String message,
            Long campaignId,
            String campaignTitle,
            String offerText,
            long verifiedCustomers,
            int targetCustomers) {
    }

    /** Confirms a verified customer visit at bill settlement. */
    public record RedeemRequest(
            @NotBlank(message = "A promo code is required")
            @Size(max = 7, message = "A promo code is 7 characters")
            String promoCode,

            // Optional and deliberately minimal: no name, phone or email is collected.
            @Size(max = 80, message = "Customer reference must be at most 80 characters")
            String customerRef,

            @Min(value = 0, message = "Bill amount cannot be negative")
            long billPaise,

            @Min(value = 0, message = "Discount cannot be negative")
            long discountPaise,

            @Pattern(regexp = "DINE_IN|TAKEAWAY|DELIVERY",
                    message = "Channel must be DINE_IN, TAKEAWAY or DELIVERY")
            String channel,

            @Size(max = 40, message = "Table reference must be at most 40 characters")
            String tableRef,

            LocalDate visitDate) {
    }

    public record RedemptionResponse(
            Long redemptionId,
            String promoCode,
            Long campaignId,
            String campaignTitle,
            long verifiedCustomers,
            int targetCustomers,
            long totalRedemptions) {
    }
}