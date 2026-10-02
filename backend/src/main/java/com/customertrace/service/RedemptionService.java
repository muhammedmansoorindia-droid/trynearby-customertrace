package com.customertrace.service;

import java.time.LocalDate;
import java.util.Locale;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.customertrace.domain.Campaign;
import com.customertrace.domain.CampaignStatus;
import com.customertrace.domain.PromoCode;
import com.customertrace.domain.PromoTierLedger;
import com.customertrace.domain.Redemption;
import com.customertrace.domain.Restaurant;
import com.customertrace.domain.ScanEvent;



import com.customertrace.exception.ApiErrors;
import com.customertrace.exception.ApiException;
import com.customertrace.repository.CampaignRepository;
import com.customertrace.repository.PromoCodeRepository;
import com.customertrace.repository.PromoTierLedgerRepository;
import com.customertrace.repository.RedemptionRepository;
import com.customertrace.repository.RestaurantRepository;
import com.customertrace.repository.ScanEventRepository;
import com.customertrace.security.CurrentUser;
import com.customertrace.service.promo.ParsedPromoCode;
import com.customertrace.service.promo.PromoCodeGenerator;
import com.customertrace.web.dto.RedemptionDtos;

/**
 * Promo-code validation, scan recording and verified redemption.
 *
 * <p><b>A scan is not a customer.</b> {@link #recordScan} records interest only; it never creates a
 * redemption and never moves the verified-customer count. Only {@link #redeem}, performed by
 * authorised restaurant staff, produces a verified visit.
 *
 * <p><b>Timezone policy.</b> Monthly limits are evaluated against {@code visitDate}, a
 * {@code DATE} column, which is timezone-free, so the calendar month is unambiguous.
 */
@Service
public class RedemptionService {

    private final PromoCodeRepository promoCodeRepository;
    private final CampaignRepository campaignRepository;
    private final RestaurantRepository restaurantRepository;
    private final ScanEventRepository scanRepository;
    private final RedemptionRepository redemptionRepository;
    private final PromoTierLedgerRepository tierLedgerRepository;
    private final PromoCodeGenerator generator;

    public RedemptionService(PromoCodeRepository promoCodeRepository,
                             CampaignRepository campaignRepository,
                             RestaurantRepository restaurantRepository,
                             ScanEventRepository scanRepository,
                             RedemptionRepository redemptionRepository,
                             PromoTierLedgerRepository tierLedgerRepository,
                             PromoCodeGenerator generator) {
        this.promoCodeRepository = promoCodeRepository;
        this.campaignRepository = campaignRepository;
        this.restaurantRepository = restaurantRepository;
        this.scanRepository = scanRepository;
        this.redemptionRepository = redemptionRepository;
        this.tierLedgerRepository = tierLedgerRepository;
        this.generator = generator;
    }

    /** The restaurant owned by the signed-in user; every scan/redemption is scoped to it. */
    private Restaurant requireOwnRestaurant(CurrentUser actor) {
        return restaurantRepository.findByUserId(actor.userId())
                .orElseThrow(() -> ApiErrors.forbidden("This action requires a restaurant account."));
    }

    /** Validates a code and returns the campaign context, recording nothing. */
    @Transactional(readOnly = true)
    public RedemptionDtos.LookupResponse lookup(CurrentUser actor, String rawCode) {
        Restaurant restaurant = requireOwnRestaurant(actor);
        ParsedPromoCode parsed = generator.parse(rawCode)
                .orElseThrow(() -> ApiErrors.badRequest("INVALID_FORMAT",
                        "That promo code is not in the expected LLNNDDX format."));

        PromoCode promo = promoCodeRepository.findByCode(parsed.letters()
                        + String.format(Locale.ROOT, "%02d%02d",
                                parsed.customerTarget(), parsed.durationDays())
                        + parsed.tier())
                .orElseThrow(() -> ApiErrors.notFound("That promo code was not recognised."));

        Campaign campaign = campaignRepository.findById(promo.getCampaignId())
                .orElseThrow(() -> ApiErrors.notFound("The campaign for that code was not found."));

        if (!campaign.getRestaurantId().equals(restaurant.getId())) {
            throw ApiErrors.notAccessible();
        }

        LocalDate today = LocalDate.now();
        long verified = redemptionRepository.countDistinctCustomers(campaign.getId());
        long redemptions = redemptionRepository.countByCampaign(campaign.getId());

        return new RedemptionDtos.LookupResponse(
                promo.getCode(), campaign.getTitle(), campaign.getOfferText(),
                restaurant.getName(), restaurant.getId(), campaign.getId(),
                campaign.getStatus(), describeValidity(campaign, today),
                describeLimit(campaign, verified), redemptions, verified,
                campaign.getTargetCustomers(), promo.isExclusiveTier(), promo.getTierLetter());
    }

    /** A short explanation of whether the campaign is currently redeemable. */
    private String describeValidity(Campaign campaign, LocalDate today) {
        if (campaign.getStatus() != CampaignStatus.ACTIVE) {
            return "This campaign is not active (status: " + campaign.getStatus() + ").";
        }
        if (today.isBefore(campaign.getStartDate())) {
            return "This campaign starts on " + campaign.getStartDate() + ".";
        }
        if (today.isAfter(campaign.getEndDate())) {
            return "This campaign expired on " + campaign.getEndDate() + ".";
        }
        return "Valid today.";
    }

    private String describeLimit(Campaign campaign, long verified) {
        if (verified >= campaign.getTargetCustomers()) {
            return "The " + campaign.getTargetCustomers() + "-customer limit has been reached.";
        }
        return verified + " of " + campaign.getTargetCustomers() + " verified customers so far.";
    }

    /**
     * Records that a code was presented.
     *
     * <p>This is interest only. It never creates a redemption and never moves the
     * verified-customer count. An invalid attempt is still recorded (with {@code valid = false})
     * so misuse stays auditable without polluting the analytics funnel.
     */
    @Transactional
    public RedemptionDtos.ScanResponse recordScan(CurrentUser actor, RedemptionDtos.ScanRequest req) {
        Restaurant restaurant = requireOwnRestaurant(actor);
        String code = normalise(req.promoCode());

        ScanEvent scan = new ScanEvent();
        scan.setRestaurantId(restaurant.getId());
        scan.setMethod(req.method() == null ? "MANUAL" : req.method().toUpperCase());
        scan.setPromoCode(code);
        scan.setDeviceLabel(req.deviceLabel());
        scan.setScannedBy(actor.userId());

        try {
            PromoCode promo = requirePromo(code);
            Campaign campaign = requireCampaign(promo);
            // A scan aimed at another restaurant's campaign is not recorded as this one's.
            requireOwnership(campaign, restaurant);
            requireRedeemable(campaign, LocalDate.now());

            scan.setCampaignId(campaign.getId());
            scan.setValid(true);
            ScanEvent saved = scanRepository.save(scan);

            return new RedemptionDtos.ScanResponse(saved.getId(), code, true, null,
                    campaign.getId(), campaign.getTitle(), campaign.getOfferText(),
                    redemptionRepository.countDistinctCustomers(campaign.getId()),
                    campaign.getTargetCustomers());
        } catch (ApiException ex) {
            // Persist the failed attempt for audit, then report the reason to staff.
            // campaignId stays null: an unrecognised code resolves to no campaign.
            scan.setValid(false);
            scan.setRejectionReason(ex.getCode());
            ScanEvent saved = scanRepository.save(scan);
            return new RedemptionDtos.ScanResponse(saved.getId(), code, false, ex.getMessage(),
                    null, null, null, 0, 0);
        }
    }

    /**
     * Confirms a verified customer visit.
     *
     * <p>Only this method increases the verified-customer count. Every check runs inside the
     * transaction, and the unique keys on {@code redemptions} turn a concurrent duplicate into a
     * constraint violation rather than a double count.
     */
    @Transactional
    public RedemptionDtos.RedemptionResponse redeem(CurrentUser actor,
                                                    RedemptionDtos.RedeemRequest req) {
        Restaurant restaurant = requireOwnRestaurant(actor);
        String code = normalise(req.promoCode());
        LocalDate visitDate = req.visitDate() == null ? LocalDate.now() : req.visitDate();

        PromoCode promo = requirePromo(code);
        Campaign campaign = requireCampaign(promo);
        requireOwnership(campaign, restaurant);
        requireRedeemable(campaign, visitDate);
        requireExclusiveTierAvailable(promo, restaurant, visitDate);

        // Idempotency for a walk-in that never scanned; the unique key is the backstop.
        if (req.customerRef() != null && !req.customerRef().isBlank()
                && redemptionRepository.existsByCampaignIdAndCustomerRefAndVisitDate(
                        campaign.getId(), req.customerRef(), visitDate)) {
            throw ApiErrors.conflict("ALREADY_REDEEMED",
                    "This customer has already redeemed this campaign today.");
        }

        Redemption redemption = new Redemption();
        redemption.setCampaignId(campaign.getId());
        redemption.setRestaurantId(restaurant.getId());
        redemption.setCreatorId(campaign.getCreatorId());
        redemption.setPromoCodeId(promo.getId());
        redemption.setPromoCode(code);
        redemption.setCustomerRef(req.customerRef());
        redemption.setVisitDate(visitDate);
        redemption.setBillPaise(req.billPaise());
        redemption.setDiscountPaise(req.discountPaise());
        redemption.setChannel(req.channel() == null ? "DINE_IN" : req.channel());
        redemption.setTableRef(req.tableRef());
        redemption.setStaffName(actor.userIdName());
        redemption.setRedeemedBy(actor.userId());
        Redemption saved = redemptionRepository.save(redemption);

        // Claim the E-J tier for this restaurant/month exactly once; the unique key makes a
        // concurrent second claim fail rather than silently succeed.
        if (promo.isExclusiveTier()) {
            claimExclusiveTier(promo, restaurant, visitDate);
        }

        return new RedemptionDtos.RedemptionResponse(
                saved.getId(), code, campaign.getId(), campaign.getTitle(),
                redemptionRepository.countDistinctCustomers(campaign.getId()),
                campaign.getTargetCustomers(),
                redemptionRepository.countByCampaign(campaign.getId()));
    }

    private String normalise(String raw) {
        return raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
    }

    private PromoCode requirePromo(String code) {
        return promoCodeRepository.findByCode(code)
                .orElseThrow(() -> ApiErrors.notFound("That promo code was not recognised."));
    }

    private Campaign requireCampaign(PromoCode promo) {
        return campaignRepository.findById(promo.getCampaignId())
                .orElseThrow(() -> ApiErrors.notFound("The campaign for that code was not found."));
    }

    private void requireOwnership(Campaign campaign, Restaurant restaurant) {
        if (!campaign.getRestaurantId().equals(restaurant.getId())) {
            throw ApiErrors.notAccessible();
        }
    }

    /** Rejects anything that is not an approved, in-date campaign with capacity left. */
    private void requireRedeemable(Campaign campaign, LocalDate on) {
        if (campaign.getStatus() != CampaignStatus.ACTIVE) {
            throw ApiErrors.badRequest("CAMPAIGN_NOT_ACTIVE",
                    "This campaign is not active (status: " + campaign.getStatus() + ").");
        }
        if (on.isBefore(campaign.getStartDate())) {
            throw ApiErrors.badRequest("NOT_STARTED",
                    "This campaign starts on " + campaign.getStartDate() + ".");
        }
        if (on.isAfter(campaign.getEndDate())) {
            throw ApiErrors.badRequest("EXPIRED",
                    "This campaign expired on " + campaign.getEndDate() + ".");
        }
        long verified = redemptionRepository.countDistinctCustomers(campaign.getId());
        if (verified >= campaign.getTargetCustomers()) {
            throw ApiErrors.badRequest("LIMIT_REACHED",
                    "This campaign has reached its " + campaign.getTargetCustomers()
                            + "-customer limit.");
        }
    }

    /** E-J may be redeemed once per restaurant per calendar month; A-D are unrestricted. */
    private void requireExclusiveTierAvailable(PromoCode promo, Restaurant restaurant,
                                               LocalDate visitDate) {
        if (!promo.isExclusiveTier()) {
            return;
        }
        String month = monthKey(visitDate);
        if (tierLedgerRepository.existsByRestaurantIdAndCalendarMonthAndTierLetter(
                restaurant.getId(), month, promo.getTierLetter())) {
            throw ApiErrors.badRequest("TIER_MONTHLY_LIMIT",
                    "This exclusive code (" + promo.getTierLetter() + ") has already been used in "
                            + month + ".");
        }
    }

    private void claimExclusiveTier(PromoCode promo, Restaurant restaurant, LocalDate visitDate) {
        PromoTierLedger entry = new PromoTierLedger();
        entry.setRestaurantId(restaurant.getId());
        entry.setCalendarMonth(monthKey(visitDate));
        entry.setTierLetter(promo.getTierLetter());
        entry.setPromoCodeId(promo.getId());
        tierLedgerRepository.save(entry);
    }

    /** {@code YYYY-MM} from a DATE column, so the month is timezone-free. */
    static String monthKey(LocalDate date) {
        return String.format(Locale.ROOT, "%04d-%02d", date.getYear(), date.getMonthValue());
    }
}
