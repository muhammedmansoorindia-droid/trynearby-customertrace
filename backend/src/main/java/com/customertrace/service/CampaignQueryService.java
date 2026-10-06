package com.customertrace.service;

import java.util.List;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.customertrace.domain.Campaign;
import com.customertrace.domain.CampaignDecision;
import com.customertrace.domain.CampaignSort;
import com.customertrace.domain.CampaignStatus;
import com.customertrace.domain.Creator;
import com.customertrace.domain.DecisionParty;
import com.customertrace.domain.PromoCode;
import com.customertrace.domain.Restaurant;
import com.customertrace.exception.ApiErrors;
import com.customertrace.repository.CampaignDecisionRepository;
import com.customertrace.repository.CampaignRepository;
import com.customertrace.repository.CampaignStatusHistoryRepository;
import com.customertrace.repository.CreatorRepository;
import com.customertrace.repository.PromoCodeRepository;
import com.customertrace.repository.RestaurantRepository;
import com.customertrace.security.CurrentUser;
import com.customertrace.web.dto.CampaignDtos;

/**
 * Read side of the campaign API: assembles DTOs and enforces ownership.
 *
 * <p>Scoping is always derived from the authenticated principal. A creator sees only their own
 * campaigns, a restaurant only its own, and an admin sees everything. Cross-tenant reads raise
 * {@code notAccessible} (404) rather than 403, so the API does not confirm the existence of
 * records the caller may not see.
 */
@Service
public class CampaignQueryService {

    private final CampaignRepository campaignRepository;
    private final CampaignDecisionRepository decisionRepository;
    private final CampaignStatusHistoryRepository historyRepository;
    private final PromoCodeRepository promoCodeRepository;
    private final CreatorRepository creatorRepository;
    private final RestaurantRepository restaurantRepository;

    public CampaignQueryService(CampaignRepository campaignRepository,
                                CampaignDecisionRepository decisionRepository,
                                CampaignStatusHistoryRepository historyRepository,
                                PromoCodeRepository promoCodeRepository,
                                CreatorRepository creatorRepository,
                                RestaurantRepository restaurantRepository) {
        this.campaignRepository = campaignRepository;
        this.decisionRepository = decisionRepository;
        this.historyRepository = historyRepository;
        this.promoCodeRepository = promoCodeRepository;
        this.creatorRepository = creatorRepository;
        this.restaurantRepository = restaurantRepository;
    }

    /** Loads a campaign the signed-in creator owns, or fails. */
    @Transactional(readOnly = true)
    public Campaign requireOwnedByCreator(CurrentUser actor, Long campaignId) {
        Creator creator = creatorRepository.findByUserId(actor.userId())
                .orElseThrow(() -> ApiErrors.forbidden("This action requires a creator account."));
        Campaign campaign = campaignRepository.findById(campaignId)
                .orElseThrow(() -> ApiErrors.notFound("Campaign"));
        if (!campaign.getCreatorId().equals(creator.getId())) {
            throw ApiErrors.notAccessible();
        }
        return campaign;
    }

    /** Loads a campaign belonging to the signed-in restaurant, or fails. */
    @Transactional(readOnly = true)
    public Campaign requireOwnedByRestaurant(CurrentUser actor, Long campaignId) {
        Restaurant restaurant = restaurantRepository.findByUserId(actor.userId())
                .orElseThrow(() -> ApiErrors.forbidden("This action requires a restaurant account."));
        Campaign campaign = campaignRepository.findById(campaignId)
                .orElseThrow(() -> ApiErrors.notFound("Campaign"));
        if (!campaign.getRestaurantId().equals(restaurant.getId())) {
            throw ApiErrors.notAccessible();
        }
        return campaign;
    }

    /**
     * Loads a campaign for administrative review.
     *
     * <p>Admins may read any campaign; existence is not hidden from them.
     */
    @Transactional(readOnly = true)
    public Campaign requireOwnedByAdmin(Long campaignId) {
        return campaignRepository.findById(campaignId)
                .orElseThrow(() -> ApiErrors.notFound("Campaign"));
    }

    @Transactional(readOnly = true)
    public List<CampaignDtos.CampaignResponse> listForCreator(CurrentUser actor, CampaignStatus status,
                                                                String term, PageRequest page) {
        Creator creator = creatorRepository.findByUserId(actor.userId())
                .orElseThrow(() -> ApiErrors.forbidden("This action requires a creator account."));
        return campaignRepository.search(status, creator.getId(), null, blankToNull(term), page)
                .map(c -> toResponse(c, actor)).getContent();
    }

    @Transactional(readOnly = true)
    public List<CampaignDtos.CampaignResponse> listForRestaurant(CurrentUser actor,
                                                                  CampaignStatus status,
                                                                  String term,
                                                                  PageRequest page) {
        Restaurant restaurant = restaurantRepository.findByUserId(actor.userId())
                .orElseThrow(() -> ApiErrors.forbidden("This action requires a restaurant account."));
        // The restaurant id comes from the token, never from the request, so one venue can never
        // widen the list to include another's campaigns.
        return campaignRepository.search(status, null, restaurant.getId(), blankToNull(term), page)
                .map(c -> toResponse(c, actor)).getContent();
    }

    @Transactional(readOnly = true)
    public List<CampaignDtos.CampaignResponse> listAll(CurrentUser actor, CampaignStatus status,
                                                      String term, PageRequest page) {
        // The actor must be threaded through: `canApprove` is computed per caller, so passing null
        // here would report every campaign as undecidable and silently strip the review queue of
        // its approval controls. The detail endpoint takes an actor for the same reason.
        return campaignRepository.search(status, null, null, blankToNull(term), page)
                .map(c -> toResponse(c, actor)).getContent();
    }
    /** An empty search box means "no term", not "match the empty string". */
    private static String blankToNull(String term) {
        return term == null || term.isBlank() ? null : term.trim();
    }

    /**
     * Resolves the requested order.
     *
     * <p>Accepts the value in any case, because {@code ?sort=newest} and {@code ?sort=NEWEST} read
     * the same to a caller. Spring's default enum binding is case-sensitive and answers an
     * unrecognised value with a 500, so the conversion is done here instead: an unknown direction is
     * a client mistake and is reported as a 400.
     */
    private static CampaignSort parseSort(String raw) {
        if (raw == null || raw.isBlank()) {
            return CampaignSort.NEWEST;
        }
        try {
            return CampaignSort.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw ApiErrors.badRequest("UNKNOWN_SORT",
                    "Sort must be one of " + java.util.Arrays.toString(CampaignSort.values()) + ".");
        }
    }

    /**
     * Builds the page request, applying the caller's chosen order.
     *
     * <p>Sorting happens in SQL through the {@code Pageable}, so pagination and ordering cannot
     * disagree the way they would if a page were sorted after being fetched.
     *
     * <p>Exposed to the controllers so all three list endpoints order identically; a list that
     * silently sorted by something else in one portal would be a genuinely confusing bug.
     */
    public static PageRequest pageOf(int page, int size, String sort) {
        Sort order = switch (parseSort(sort)) {
            case OLDEST -> Sort.by(Sort.Direction.ASC, "createdAt");
            case NEWEST -> Sort.by(Sort.Direction.DESC, "createdAt");
            // The column is `updated_at`; the entity property Spring sorts by is `updatedAt`.
            case UPDATED -> Sort.by(Sort.Direction.DESC, "updatedAt");
            case TARGET -> Sort.by(Sort.Direction.DESC, "targetCustomers");
        };
        // Deterministic tie-break, so two campaigns created in the same millisecond cannot swap
        // places between pages and hide one another.
        return PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100),
                order.and(Sort.by(Sort.Direction.DESC, "id")));
    }

    @Transactional(readOnly = true)
    public List<CampaignDtos.StatusHistoryResponse> historyFor(Long campaignId) {
        return historyRepository.findByCampaignIdOrderByChangedAtAsc(campaignId).stream()
                .map(h -> new CampaignDtos.StatusHistoryResponse(h.getId(), h.getFromStatus(),
                        h.getToStatus(), h.getReason(), null, h.getChangedAt()))
                .toList();
    }
/** Maps an entity to the read model, including the decisions recorded by each party. */
    @Transactional(readOnly = true)
    public CampaignDtos.CampaignResponse toResponse(Campaign campaign, CurrentUser actor) {
        // Most recent code wins, so a campaign with historic duplicates still renders.
        PromoCode promo = promoCodeRepository.findByCampaignIdOrderByIdDesc(campaign.getId())
                .stream().findFirst().orElse(null);
        String adminReason = decisionRepository
                .findByCampaignIdAndParty(campaign.getId(), DecisionParty.ADMIN)
                .map(CampaignDecision::getReason).orElse(null);
        String restaurantReason = decisionRepository
                .findByCampaignIdAndParty(campaign.getId(), DecisionParty.RESTAURANT)
                .map(CampaignDecision::getReason).orElse(null);

        return new CampaignDtos.CampaignResponse(
                campaign.getId(),
                campaign.getCreatorId(),
                creatorRepository.findById(campaign.getCreatorId()).map(Creator::getDisplayName)
                        .orElse(null),
                campaign.getRestaurantId(),
                restaurantRepository.findById(campaign.getRestaurantId()).map(Restaurant::getName)
                        .orElse(null),
                campaign.getTitle(),
                campaign.getDescription(),
                campaign.getOfferText(),
                campaign.getDiscountPercent(),
                campaign.getMinBillPaise(),
                campaign.getTargetCustomers(),
                campaign.getDurationDays(),
                campaign.getStartDate(),
                campaign.getEndDate(),
                campaign.getStatus(),
                campaign.getAdminDecision(),
                campaign.getRestaurantDecision(),
                adminReason,
                restaurantReason,
                promo == null ? null : promo.getCode(),
                campaign.getResubmissionCount(),
                canSubmit(campaign),
                canDecide(campaign, actor),
                campaign.getSubmittedAt(),
                campaign.getActivatedAt(),
                campaign.getCreatedAt());
    }

    /** True when the owner may still submit (draft or previously rejected). */
    private boolean canSubmit(Campaign campaign) {
        return campaign.getStatus() == CampaignStatus.DRAFT
                || campaign.getStatus() == CampaignStatus.REJECTED;
    }

    /**
     * True when the signed-in user may record the decision for their party right now.
     *
     * <p>Used only to grey out buttons; the server re-checks authorization regardless.
     */
    private boolean canDecide(Campaign campaign, CurrentUser actor) {
        if (actor == null || campaign.getStatus() != CampaignStatus.PENDING) {
            return false;
        }
        if (actor.isAdmin()) {
            return campaign.getAdminDecision() == null;
        }
        if (actor.isRestaurant()) {
            Restaurant restaurant = restaurantRepository.findByUserId(actor.userId()).orElse(null);
            return restaurant != null
                    && restaurant.getId().equals(campaign.getRestaurantId())
                    && campaign.getRestaurantDecision() == null;
        }
        return false;
    }
}