package com.customertrace.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * One party's decision on a campaign.
 *
 * <p>The unique constraint on {@code (campaign_id, party)} guarantees a party records at most
 * one decision per campaign, so a second approval cannot overwrite the first.
 */
@Entity
@Table(name = "campaign_decisions")
public class CampaignDecision {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "campaign_id", nullable = false)
    private Long campaignId;

    @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
    @Column(name = "party", nullable = false, length = 16)
    private DecisionParty party;

    @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
    @Column(name = "decision", nullable = false, length = 16)
    private Decision decision;

    @Column(name = "reason", length = 400)
    private String reason;

    @Column(name = "decided_by", nullable = false)
    private Long decidedBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    public CampaignDecision() {
    }

    public CampaignDecision(Long campaignId, DecisionParty party, Decision decision,
                            String reason, Long decidedBy) {
        this.campaignId = campaignId;
        this.party = party;
        this.decision = decision;
        this.reason = reason;
        this.decidedBy = decidedBy;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getCampaignId() {
        return campaignId;
    }

    public void setCampaignId(Long campaignId) {
        this.campaignId = campaignId;
    }

    public DecisionParty getParty() {
        return party;
    }

    public void setParty(DecisionParty party) {
        this.party = party;
    }

    public Decision getDecision() {
        return decision;
    }

    public void setDecision(Decision decision) {
        this.decision = decision;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public Long getDecidedBy() {
        return decidedBy;
    }

    public void setDecidedBy(Long decidedBy) {
        this.decidedBy = decidedBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}