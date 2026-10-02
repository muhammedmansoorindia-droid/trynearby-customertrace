package com.customertrace.service;

import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.customertrace.domain.AuditLog;
import com.customertrace.domain.Campaign;
import com.customertrace.domain.CampaignStatus;
import com.customertrace.domain.Creator;
import com.customertrace.domain.Decision;
import com.customertrace.domain.DecisionParty;
import com.customertrace.domain.PromoCode;
import com.customertrace.domain.Restaurant;
import com.customertrace.exception.ApiErrors;
import com.customertrace.repository.AuditLogRepository;
import com.customertrace.repository.CampaignDecisionRepository;
import com.customertrace.repository.CampaignRepository;
import com.customertrace.repository.CampaignStatusHistoryRepository;
import com.customertrace.repository.CreatorRepository;
import com.customertrace.repository.NotificationRepository;
import com.customertrace.repository.PromoCodeRepository;
import com.customertrace.repository.PromoTierLedgerRepository;
import com.customertrace.repository.RestaurantRepository;
import com.customertrace.repository.UserRepository;
import com.customertrace.security.CurrentUser;
import com.customertrace.service.promo.PromoCodeGenerator;
import com.customertrace.web.dto.CampaignDtos;

import jakarta.persistence.EntityManager;

/**
 * Campaign lifecycle with dual approval.
 *
 * <p><b>The rule:</b> a submitted campaign becomes {@link CampaignStatus#ACTIVE} only when the
 * TryNearby administrator <em>and</em> the associated restaurant have both approved. Either
 * party rejecting sends it to {@link CampaignStatus#REJECTED}, from which only an explicit
 * resubmission can revive it.
 *
 * <p><b>Concurrency.</b> Decisions are taken against a pessimistically locked row
 * ({@code findByIdForUpdate}) and the campaign also carries an optimistic {@code @Version}, so a
 * second approver blocks and re-evaluates the changed state instead of overwriting it.
 *
 * <p>Authorization is decided here from the authenticated principal; creator and restaurant ids
 * are read from the database, never trusted from the request.
 */
@Service
public class CampaignWorkflowService {

    private static final Logger log = LoggerFactory.getLogger(CampaignWorkflowService.class);

    private final CampaignRepository campaignRepository;
    private final CampaignDecisionRepository decisionRepository;
    private final CampaignStatusHistoryRepository historyRepository;
    private final PromoCodeRepository promoCodeRepository;
    private final PromoTierLedgerRepository tierLedgerRepository;
    private final NotificationRepository notificationRepository;
    private final AuditLogRepository auditLogRepository;
    private final RestaurantRepository restaurantRepository;
    private final CreatorRepository creatorRepository;
    private final UserRepository userRepository;
    private final PromoCodeGenerator promoCodeGenerator;
    private final EntityManager entityManager;

    public CampaignWorkflowService(CampaignRepository campaignRepository,
                                   CampaignDecisionRepository decisionRepository,
                                   CampaignStatusHistoryRepository historyRepository,
                                   PromoCodeRepository promoCodeRepository,
                                   PromoTierLedgerRepository tierLedgerRepository,
                                   NotificationRepository notificationRepository,
                                   AuditLogRepository auditLogRepository,
                                   RestaurantRepository restaurantRepository,
                                   CreatorRepository creatorRepository,
                                   UserRepository userRepository,
                                   PromoCodeGenerator promoCodeGenerator,
                                   EntityManager entityManager) {
        this.campaignRepository = campaignRepository;
        this.decisionRepository = decisionRepository;
        this.historyRepository = historyRepository;
        this.promoCodeRepository = promoCodeRepository;
        this.tierLedgerRepository = tierLedgerRepository;
        this.notificationRepository = notificationRepository;
        this.auditLogRepository = auditLogRepository;
        this.restaurantRepository = restaurantRepository;
        this.creatorRepository = creatorRepository;
        this.userRepository = userRepository;
        this.promoCodeGenerator = promoCodeGenerator;
        this.entityManager = entityManager;
    }

    /** Creates a DRAFT campaign owned by the authenticated creator. */
    @Transactional
    public Campaign create(CurrentUser actor, CampaignDtos.CreateCampaignRequest request) {
        Creator creator = requireCreator(actor);

        Restaurant restaurant = restaurantRepository.findById(request.restaurantId())
                .orElseThrow(() -> ApiErrors.notFound("Restaurant"));

        Campaign campaign = new Campaign();
        campaign.setCreatorId(creator.getId());
        campaign.setRestaurantId(restaurant.getId());
        campaign.setTitle(request.title());
        campaign.setDescription(request.description());
        campaign.setOfferText(request.offerText());
        campaign.setDiscountPercent(request.discountPercent());
        campaign.setMinBillPaise(request.minBillPaise());
        campaign.setTargetCustomers(request.targetCustomers());
        campaign.setDurationDays(request.durationDays());
        campaign.setStartDate(request.startDate());
        campaign.setEndDate(request.endDate());
        campaign.setStatus(CampaignStatus.DRAFT);

        Campaign saved = campaignRepository.save(campaign);
        recordHistory(saved, null, CampaignStatus.DRAFT, "Campaign created", actor);
/**
     * Submits a draft (or a previously rejected campaign) for review.
     *
     * <p>A promo code is generated here because the NN/DD segments derive from the campaign's own
     * customer cap and duration and must be encodable in two digits.
     */
    @Transactional
    public Campaign submit(CurrentUser actor, Long campaignId) {
        Campaign campaign = loadOwnedByCreator(actor, campaignId);

        if (campaign.getStatus() != CampaignStatus.DRAFT
                && campaign.getStatus() != CampaignStatus.REJECTED) {
            throw ApiErrors.conflict("INVALID_TRANSITION",
                    "Only a draft or rejected campaign can be submitted. This campaign is "
                            + campaign.getStatus() + ".");
        }

        // Reject values that cannot be encoded as two digits before generating anything.
        promoCodeGenerator.validateEncodable(campaign.getTargetCustomers(),
                campaign.getDurationDays());
        assignPromoCode(campaign);

        CampaignStatus from = campaign.getStatus();
        campaign.setStatus(CampaignStatus.PENDING);
        campaign.setSubmittedAt(Instant.now());
        // A resubmission clears both prior decisions so the workflow restarts cleanly.
        campaign.setAdminDecision(null);
        campaign.setRestaurantDecision(null);
        if (from == CampaignStatus.REJECTED) {
            campaign.setResubmissionCount(campaign.getResubmissionCount() + 1);
        }
        Campaign saved = campaignRepository.save(campaign);
        decisionRepository.deleteByCampaignId(saved.getId());

        recordHistory(saved, from, CampaignStatus.PENDING,
                from == CampaignStatus.REJECTED ? "Resubmitted for review" : "Submitted for review",
                actor);

        notifyRestaurantOwner(saved, "New campaign awaiting your approval", saved.getTitle());
        notifyCreator(saved, "Campaign submitted",
                saved.getTitle() + " is now under review.");
        return saved;
    }

    /**
     * Records one party's decision.
     *
     * <p>Authorization is by role <em>and</em> by association: an admin may only record the ADMIN
     * decision, and only the restaurant the campaign belongs to may record the RESTAURANT one.
     *
     * <p>The row is locked for the duration so a concurrent decision cannot slip past the
     * dual-approval gate.
     */
    @Transactional
    public Campaign decide(CurrentUser actor, Long campaignId, DecisionParty party,
                           CampaignDtos.DecisionRequest request) {
        Campaign campaign = campaignRepository.findByIdForUpdate(campaignId)
                .orElseThrow(() -> ApiErrors.notFound("Campaign"));

        if (party == DecisionParty.ADMIN && !actor.isAdmin()) {
            throw ApiErrors.forbidden("Only a TryNearby administrator can record the "
                    + "administrator decision.");
        }
        if (party == DecisionParty.RESTAURANT) {
            Long actorRestaurantId = restaurantForActor(actor).getId();
            if (!campaign.getRestaurantId().equals(actorRestaurantId)) {
                // Reported as not-accessible so the API does not confirm another tenant's data.
                throw ApiErrors.notAccessible();
            }
        }

        if (campaign.getStatus() != CampaignStatus.PENDING) {
            throw ApiErrors.conflict("INVALID_TRANSITION",
                    "Only a campaign awaiting review can be decided. This one is "
                            + campaign.getStatus() + ".");
        }

        CampaignStatus from = campaign.getStatus();
        Decision decision = request.decision();

        if (party == DecisionParty.ADMIN) {
            campaign.setAdminDecision(decision);
        } else {
            campaign.setRestaurantDecision(decision);
        }

        // Persist the individual decision. The unique (campaign, party) key makes a second
        // decision by the same party fail rather than overwrite the first.
        decisionRepository.deleteByCampaignIdAndParty(campaignId, party);
        decisionRepository.save(new CampaignDecision(campaignId, party, decision,
                request.reason(), actor.userId()));

        CampaignStatus next;
        String note;
        if (decision == Decision.REJECTED) {
            // A single rejection blocks activation outright.
            next = CampaignStatus.REJECTED;
            note = party + " rejected the campaign"
                    + (request.reason() == null || request.reason().isBlank()
                    ? "" : ": " + request.reason());
        } else if (campaign.isFullyApproved()) {
            next = CampaignStatus.ACTIVE;
            campaign.setActivatedAt(Instant.now());
            note = "Approved by both TryNearby and the restaurant; campaign is now active.";
        } else {
            // Approved by one party only: stays PENDING until the other decides.
            next = CampaignStatus.PENDING;
            note = party + " approved; awaiting the other approval.";
        }

        campaign.setStatus(next);
        Campaign saved = campaignRepository.save(campaign);
        recordHistory(saved, from, next, note, actor);

        auditLogRepository.save(AuditLog.of(actor.userId(), actor.userIdName(),
                "CAMPAIGN_DECISION_" + party + "_" + decision, "CAMPAIGN", campaignId,
                "status " + from + " -> " + next, null));

        notifyDecision(saved, party, decision, request.reason());
        return saved;
    }
/**
     * Generates and attaches a promo code for the campaign.
     *
     * <p>Exclusive (E-J) tiers are probed against the tier ledger for this restaurant and month,
     * so two campaigns cannot claim the same scarce tier.
     */
    private void assignPromoCode(Campaign campaign) {
        java.time.LocalDate month = campaign.getStartDate().withDayOfMonth(1);
        String calendarMonth = String.format("%04d-%02d", month.getYear(), month.getMonthValue());

        String code = promoCodeGenerator
                .withCodeExistsChecker(promoCodeRepository::existsByCode)
                .withExclusiveTierTakenChecker(tier ->
                        tierLedgerRepository.existsByRestaurantIdAndCalendarMonthAndTierLetter(
                                campaign.getRestaurantId(), calendarMonth,
                                String.valueOf(tier)))
                .generate(campaign.getTargetCustomers(), campaign.getDurationDays());

        PromoCode promo = new PromoCode();
        promo.setCampaignId(campaign.getId());
        promo.setCode(code);
        promo.setClientTarget(campaign.getTargetCustomers());
        promo.setDurationDays(campaign.getDurationDays());
        promo.setTierLetter(String.valueOf(code.charAt(6)));
        promo.setExclusiveTier(promoCodeGenerator.isExclusiveTier(code.charAt(6)));
        promo.setActive(true);
        promoCodeRepository.save(promo);
    }

    /** Loads a campaign only if the authenticated creator owns it. */
    private Campaign loadOwnedByCreator(CurrentUser actor, Long campaignId) {
        Creator creator = requireCreator(actor);
        Campaign campaign = campaignRepository.findById(campaignId)
                .orElseThrow(() -> ApiErrors.notFound("Campaign"));
        if (!campaign.getCreatorId().equals(creator.getId())) {
            throw ApiErrors.notAccessible();
        }
        return campaign;
    }

    private Creator requireCreator(CurrentUser actor) {
        return creatorRepository.findByUserId(actor.userId())
                .orElseThrow(() -> ApiErrors.forbidden(
                        "This action requires a creator account."));
    }

    /** The restaurant owned by the authenticated user. */
    private Restaurant restaurantForActor(CurrentUser actor) {
        return restaurantRepository.findByUserId(actor.userId())
                .orElseThrow(() -> ApiErrors.forbidden(
                        "This action requires a restaurant account."));
    }

    private void recordHistory(Campaign campaign, CampaignStatus from, CampaignStatus to,
                               String reason, CurrentUser actor) {
        com.customertrace.domain.CampaignStatusHistory entry =
                new com.customertrace.domain.CampaignStatusHistory();
        entry.setCampaignId(campaign.getId());
        entry.setFromStatus(from);
        entry.setToStatus(to);
        entry.setReason(reason);
        entry.setChangedBy(actor.userId());
        entry.setChangedAt(Instant.now());
        historyRepository.save(entry);
    }

    /** Fans a decision out to the creator and to whichever party has not decided yet. */
    private void notifyDecision(Campaign campaign, DecisionParty party, Decision decision,
                                String reason) {
        String detail = reason == null || reason.isBlank() ? "" : " Reason: " + reason;
        if (decision == Decision.REJECTED) {
            notifyCreator(campaign, "Campaign rejected by " + party,
                    campaign.getTitle() + " was rejected." + detail);
        } else if (campaign.getStatus() == CampaignStatus.ACTIVE) {
            notifyCreator(campaign, "Campaign approved",
                    campaign.getTitle() + " was approved by both parties and is now active.");
            notifyRestaurantOwner(campaign, "Campaign approved",
                    campaign.getTitle() + " is now active.");
        } else {
            notifyCreator(campaign, "Approval recorded",
                    party + " approved " + campaign.getTitle()
                            + "; awaiting the remaining approval.");
            if (party == DecisionParty.ADMIN) {
                notifyRestaurantOwner(campaign, "Campaign awaiting your approval",
                        campaign.getTitle() + " was approved by TryNearby and needs your decision.");
            }
        }
    }

    private void notifyCreator(Campaign campaign, String title, String body) {
        Creator creator = creatorRepository.findById(campaign.getCreatorId()).orElse(null);
        if (creator == null) {
            return;
        }
        saveNotification("CREATOR", creator.getUserId(), title, body, "/creator/campaigns");
    }

    private void notifyRestaurantOwner(Campaign campaign, String title, String body) {
        Restaurant restaurant = restaurantRepository.findById(campaign.getRestaurantId()).orElse(null);
        if (restaurant == null) {
            return;
        }
        saveNotification("RESTAURANT", restaurant.getUserId(), title, body,
                "/restaurant/campaigns");
    }

    private void saveNotification(String audience, Long recipientId, String title, String body,
                                  String link) {
        Notification notification = new Notification();
        notification.setType("CAMPAIGN_" + title.toUpperCase().replaceAll("[^A-Z]+", "_"));
        notification.setAudience(audience);
        notification.setRecipientId(recipientId);
        notification.setTitle(title);
        notification.setBody(body);
        notification.setLink(link);
        notification.setCreatedAt(Instant.now());
        notificationRepository.save(notification);
    }
}
        return saved;
    }