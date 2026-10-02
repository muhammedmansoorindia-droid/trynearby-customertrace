package com.customertrace.service;

import java.util.List;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.customertrace.domain.Campaign;
import com.customertrace.domain.CampaignDecision;
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
                                                                PageRequest page) {
        Creator creator = creatorRepository.findByUserId(actor.userId())
                .orElseThrow(() -> ApiErrors.forbidden("This action requires a creator account."));
        return campaignRepository.search(status, creator.getId(), null, page)
                .map(c -> toResponse(c, actor)).getContent();
    }

    @Transactional(readOnly = true)
    public List<CampaignDtos.CampaignResponse> listForRestaurant(CurrentUser actor,
                                                                  CampaignStatus status,
                                                                  PageRequest page) {
        Restaurant restaurant = restaurantRepository.findByUserId(actor.userId())
                .orElseThrow(() -> ApiErrors.forbidden("This action requires a restaurant account."));
        return campaignRepository.search(status, null, restaurant.getId(), page)
                .map(c -> toResponse(c, actor)).getContent();
    }

    @Transactional(readOnly = true)
    public List<CampaignDtos.CampaignResponse> listAll(CampaignStatus status, PageRequest page) {
        return campaignRepository.search(status, null, null, page)
                .map(c -> toResponse(c, null)).getContent();
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