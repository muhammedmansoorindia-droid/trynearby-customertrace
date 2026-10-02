package com.customertrace.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.customertrace.domain.CampaignDecision;

public interface CampaignDecisionRepository extends JpaRepository<CampaignDecision, Long> {

    List<CampaignDecision> findByCampaignId(Long campaignId);

    /**
     * Clears a party's prior decision.
     *
     * <p>Called on resubmission so both parties record a fresh decision against the new
     * submission rather than inheriting a stale verdict.
     */
    void deleteByCampaignId(Long campaignId);

    void deleteByCampaignIdAndParty(Long campaignId,
                                    com.customertrace.domain.DecisionParty party);
}