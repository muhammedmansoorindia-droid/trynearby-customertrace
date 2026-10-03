package com.customertrace.service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.customertrace.domain.Campaign;
import com.customertrace.domain.CampaignStatus;
import com.customertrace.domain.Creator;
import com.customertrace.domain.PromoCode;
import com.customertrace.domain.Restaurant;
import com.customertrace.exception.ApiErrors;
import com.customertrace.repository.BookingRepository;
import com.customertrace.repository.CampaignReportRepository;
import com.customertrace.repository.CampaignRepository;
import com.customertrace.repository.CreatorRepository;
import com.customertrace.repository.PromoCodeRepository;
import com.customertrace.repository.RestaurantRepository;
import com.customertrace.repository.SubscriptionRepository;
import com.customertrace.security.CurrentUser;
import com.customertrace.web.dto.ReportDtos;

/**
 * Role-scoped reporting.
 *
 * <p>Every figure is read from the database. Ownership is resolved from the authenticated
 * principal — a restaurant reads only its own numbers, a creator only its own — so cross-tenant
 * access is prevented here rather than by filtering in the browser.
 *
 * <p><b>Dates.</b> The requested range is inclusive of both dates and becomes a half-open
 * {@code [from, to+1)} UTC instant range. An inverted range is rejected, not silently zeroed.
 */
@Service
public class ReportService {

    /** Default window when the caller supplies no dates. */
    private static final int DEFAULT_RANGE_DAYS = 30;

    /** Guard so a bad request cannot ask for an unbounded scan of the table. */
    private static final int MAX_RANGE_DAYS = 366;

    private static final int MAX_CAMPAIGN_ROWS = 200;

    private final CampaignReportRepository reports;
    private final CampaignRepository campaignRepository;
    private final RestaurantRepository restaurantRepository;
    private final CreatorRepository creatorRepository;
    private final BookingRepository bookingRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final PromoCodeRepository promoCodeRepository;

    public ReportService(CampaignReportRepository reports, CampaignRepository campaignRepository,
                         RestaurantRepository restaurantRepository,
                         CreatorRepository creatorRepository,
                         BookingRepository bookingRepository,
                         SubscriptionRepository subscriptionRepository,
                         PromoCodeRepository promoCodeRepository) {
        this.reports = reports;
        this.campaignRepository = campaignRepository;
        this.restaurantRepository = restaurantRepository;
        this.creatorRepository = creatorRepository;
        this.bookingRepository = bookingRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.promoCodeRepository = promoCodeRepository;
    }

    /** A validated inclusive range plus the half-open instants the queries expect. */
    public record Range(LocalDate from, LocalDate to, Instant start, Instant end) {

        public static Range of(LocalDate from, LocalDate to) {
            LocalDate start = from != null ? from : LocalDate.now().minusDays(DEFAULT_RANGE_DAYS);
            LocalDate end = to != null ? to : LocalDate.now();
            if (end.isBefore(start)) {
                throw ApiErrors.badRequest("INVALID_RANGE",
                        "The end date must not be before the start date.");
            }
            if (start.plusDays(MAX_RANGE_DAYS).isBefore(end)) {
                throw ApiErrors.badRequest("RANGE_TOO_WIDE",
                        "The reporting range may not exceed " + MAX_RANGE_DAYS + " days.");
            }
            return new Range(start, end,
                    start.atStartOfDay(ZoneOffset.UTC).toInstant(),
                    end.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant());
        }
    }

    /**
     * Conversion rate: verified customers as a percentage of scans.
     *
     * <p>Defined once here so every portal computes it identically. Zero scans yields zero rather
     * than dividing by zero.
     */
    static double conversionRate(long verifiedCustomers, long scans) {
        return scans == 0 ? 0d : Math.round((verifiedCustomers * 10000d) / scans) / 100d;
    }

    private Restaurant requireRestaurant(CurrentUser actor) {
        return restaurantRepository.findByUserId(actor.userId()).orElseThrow(
                () -> ApiErrors.forbidden("This report requires a restaurant account."));
    }

    private Creator requireCreator(CurrentUser actor) {
        return creatorRepository.findByUserId(actor.userId()).orElseThrow(
                () -> ApiErrors.forbidden("This report requires a creator account."));
    }

// ── Restaurant scope ─────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public ReportDtos.SummaryResponse restaurantSummary(CurrentUser actor, LocalDate from,
                                                         LocalDate to) {
        Restaurant restaurant = requireRestaurant(actor);
        Range range = Range.of(from, to);
        return summary(restaurant.getId(), null, range);
    }

    @Transactional(readOnly = true)
    public List<ReportDtos.CampaignPerformanceResponse> restaurantCampaigns(
            CurrentUser actor, LocalDate from, LocalDate to) {
        Restaurant restaurant = requireRestaurant(actor);
        Range range = Range.of(from, to);
        List<Campaign> campaigns = campaignRepository.search(null, null, restaurant.getId(),
                PageRequest.of(0, MAX_CAMPAIGN_ROWS)).getContent();
        return campaignPerformance(campaigns, range);
    }

    @Transactional(readOnly = true)
    public ReportDtos.TrendResponse restaurantTrend(CurrentUser actor, LocalDate from, LocalDate to) {
        return trend(requireRestaurant(actor).getId(), null, Range.of(from, to));
    }

    @Transactional(readOnly = true)
    public ReportDtos.StatusBreakupResponse restaurantStatusBreakup(CurrentUser actor) {
        Restaurant restaurant = requireRestaurant(actor);
        List<ReportDtos.StatusBreakupEntry> entries = new ArrayList<>();
        for (CampaignStatus status : CampaignStatus.values()) {
            entries.add(new ReportDtos.StatusBreakupEntry(status.name(),
                    campaignRepository.findByRestaurantAndStatus(restaurant.getId(), status).size()));
        }
        return new ReportDtos.StatusBreakupResponse(entries);
    }

    // ── Creator scope ───────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public ReportDtos.SummaryResponse creatorSummary(CurrentUser actor, LocalDate from, LocalDate to) {
        Range range = Range.of(from, to);
        return summary(null, requireCreator(actor).getId(), range);
    }

    @Transactional(readOnly = true)
    public List<ReportDtos.CampaignPerformanceResponse> creatorCampaigns(
            CurrentUser actor, LocalDate from, LocalDate to) {
        Creator creator = requireCreator(actor);
        Range range = Range.of(from, to);
        List<Campaign> campaigns = campaignRepository.search(null, creator.getId(), null,
                PageRequest.of(0, MAX_CAMPAIGN_ROWS)).getContent();
        return campaignPerformance(campaigns, range);
    }

    @Transactional(readOnly = true)
    public ReportDtos.TrendResponse creatorTrend(CurrentUser actor, LocalDate from, LocalDate to) {
        return trend(null, requireCreator(actor).getId(), Range.of(from, to));
    }

    @Transactional(readOnly = true)
    public ReportDtos.StatusBreakupResponse creatorStatusBreakup(CurrentUser actor) {
        Creator creator = requireCreator(actor);
        List<ReportDtos.StatusBreakupEntry> entries = new ArrayList<>();
        for (CampaignStatus status : CampaignStatus.values()) {
            entries.add(new ReportDtos.StatusBreakupEntry(status.name(),
                    campaignRepository.search(status, creator.getId(), null,
                            PageRequest.of(0, 1)).getTotalElements()));
        }
        return new ReportDtos.StatusBreakupResponse(entries);
    }

    // ── Shared helpers ──────────────────────────────────────────────────────

    /** Headline counts for one tenant; either side may be null for platform scope. */
    private ReportDtos.SummaryResponse summary(Long restaurantId, Long creatorId, Range range) {
        long scans = reports.countValidScansScoped(restaurantId, creatorId,
                range.start(), range.end());
        long redemptions = reports.countRedemptionsScoped(restaurantId, creatorId,
                range.start(), range.end());
        long customers = reports.countVerifiedCustomersScoped(restaurantId, creatorId,
                range.start(), range.end());
        long discount = reports.sumDiscountScoped(restaurantId, creatorId,
                range.start(), range.end());

        return new ReportDtos.SummaryResponse(range.from(), range.to(), scans, redemptions,
                customers, conversionRate(customers, scans), discount, 0);
    }

    /** Daily scans and verified customers; only days that have data are returned. */
    private ReportDtos.TrendResponse trend(Long restaurantId, Long creatorId, Range range) {
        Map<LocalDate, long[]> byDay = new LinkedHashMap<>();
        reports.dailyScans(restaurantId, creatorId, range.start(), range.end())
                .forEach(row -> byDay
                        .computeIfAbsent(row.getDay(), k -> new long[2])[0] = row.getScans());
        reports.dailyRedemptions(restaurantId, creatorId, range.start(), range.end())
                .forEach(row -> byDay
                        .computeIfAbsent(row.getDay(), k -> new long[2])[1] =
                        row.getRedemptions());

        List<ReportDtos.TrendPoint> points = byDay.entrySet().stream()
                .map(e -> new ReportDtos.TrendPoint(e.getKey(), e.getValue()[0], e.getValue()[1]))
                .toList();
        return new ReportDtos.TrendResponse(points);
    }

    /** Per-campaign rows, each with its own scan/redemption/customer counts. */
    private List<ReportDtos.CampaignPerformanceResponse> campaignPerformance(
            List<Campaign> campaigns, Range range) {
        List<ReportDtos.CampaignPerformanceResponse> result = new ArrayList<>();
        for (Campaign campaign : campaigns) {
            long scans = reports.countValidScans(campaign.getId(), range.start(), range.end());
            long redemptions = reports.countRedemptions(campaign.getId(), range.start(),
                    range.end());
            long customers = reports.countVerifiedCustomers(campaign.getId(), range.start(),
                    range.end());
            long discount = reports.sumDiscountScoped(campaign.getRestaurantId(),
                    campaign.getCreatorId(), range.start(), range.end());

            result.add(new ReportDtos.CampaignPerformanceResponse(
                    campaign.getId(),
                    campaign.getTitle(),
                    campaign.getStatus().name(),
                    restaurantRepository.findById(campaign.getRestaurantId())
                            .map(Restaurant::getName).orElse(null),
                    creatorRepository.findById(campaign.getCreatorId())
                            .map(Creator::getDisplayName).orElse(null),
                    promoCodeRepository.findByCampaignIdOrderByIdDesc(campaign.getId())
                            .stream().findFirst().map(PromoCode::getCode).orElse(null),
                    campaign.getTargetCustomers(),
                    scans, redemptions, customers,
                    conversionRate(customers, scans), discount));
        }
        return result;
    }

    // ── Platform scope (administrators only; the route enforces the role) ───

    @Transactional(readOnly = true)
    public ReportDtos.PlatformSummaryResponse platformSummary(LocalDate from, LocalDate to) {
        Range range = Range.of(from, to);
        long scans = reports.countValidScansScoped(null, null, range.start(), range.end());
        long redemptions = reports.countRedemptionsScoped(null, null,
                range.start(), range.end());
        long customers = reports.countVerifiedCustomersScoped(null, null,
                range.start(), range.end());

        return new ReportDtos.PlatformSummaryResponse(
                restaurantRepository.count(),
                creatorRepository.count(),
                reports.countAll(),
                reports.countByStatus(CampaignStatus.ACTIVE.name()),
                reports.countByStatus(CampaignStatus.PENDING.name()),
                reports.countByStatus(CampaignStatus.REJECTED.name()),
                bookingRepository.count(),
                subscriptionRepository.countByStatus("ACTIVE"),
                scans, redemptions, customers, conversionRate(customers, scans));
    }

    @Transactional(readOnly = true)
    public List<ReportDtos.CampaignPerformanceResponse> platformCampaigns(LocalDate from,
                                                                         LocalDate to) {
        Range range = Range.of(from, to);
        List<Campaign> campaigns = campaignRepository.search(null, null, null,
                PageRequest.of(0, MAX_CAMPAIGN_ROWS)).getContent();
        return campaignPerformance(campaigns, range);
    }

    @Transactional(readOnly = true)
    public ReportDtos.TrendResponse platformTrend(LocalDate from, LocalDate to) {
        return trend(null, null, Range.of(from, to));
    }
}
