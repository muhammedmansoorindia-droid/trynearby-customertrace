package com.customertrace.web.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Report and analytics payloads.
 *
 * <p><b>Metric definitions are fixed here so every portal agrees:</b>
 * <ul>
 *   <li>{@code scans} — valid QR/manual scans. Interest, never a customer.</li>
 *   <li>{@code redemptions} — verified visit records.</li>
 *   <li>{@code verifiedCustomers} — <em>distinct</em> customer references.</li>
 *   <li>{@code conversionRatePercent} — {@code verifiedCustomers / scans * 100}, and 0 when
 *       there are no scans, so an empty dataset never divides by zero.</li>
 * </ul>
 */
public final class ReportDtos {

    private ReportDtos() {
    }

    /** Headline figures for a date range, scoped to one tenant. */
    public record SummaryResponse(
            LocalDate from,
            LocalDate to,
            long scans,
            long redemptions,
            long verifiedCustomers,
            double conversionRatePercent,
            long discountPaise,
            long billPaise) {
    }

    /** One campaign's performance within the range. */
    public record CampaignPerformanceResponse(
            Long campaignId,
            String title,
            String status,
            String restaurantName,
            String creatorName,
            String promoCode,
            int targetCustomers,
            long scans,
            long redemptions,
            long verifiedCustomers,
            double conversionRatePercent,
            long discountPaise) {
    }

    /** Per-day activity for the trend chart. Only days with data are returned. */
    public record TrendPoint(LocalDate day, long scans, long verifiedCustomers) {
    }

    public record TrendResponse(List<TrendPoint> points) {
    }

    /** Aggregated counts per campaign status, for the status breakdown chart. */
    public record StatusBreakupEntry(String status, long count) {
    }

    public record StatusBreakupResponse(List<StatusBreakupEntry> entries) {
    }

    /** Platform-wide figures. Administrator only. */
    public record PlatformSummaryResponse(
            long totalRestaurants,
            long totalCreators,
            long totalCampaigns,
            long activeCampaigns,
            long pendingCampaigns,
            long rejectedCampaigns,
            long totalBookings,
            long activeSubscriptions,
            long totalScans,
            long totalRedemptions,
            long totalVerifiedCustomers,
            double conversionRatePercent) {
    }

    /** A single notification, as shown in the in-app list. */
    public record NotificationResponse(
            Long id,
            String type,
            String title,
            String body,
            String link,
            boolean read,
            Instant createdAt) {
    }

    /** Paged notifications plus the unread count, so the badge needs no extra round trip. */
    public record NotificationPageResponse(
            List<NotificationResponse> items,
            long unreadCount,
            int page,
            int size,
            long total) {
    }
}