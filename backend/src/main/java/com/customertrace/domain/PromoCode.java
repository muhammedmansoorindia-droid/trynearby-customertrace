package com.customertrace.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A promo code in {@code LLNNDDX} form: two letters, a two-digit customer cap, a two-digit
 * duration in days, and a tier letter A-J.
 *
 * <p>{@code clientTarget} and {@code durationDays} mirror the NN/DD segments so validity and
 * usage limits are checked with an indexed lookup instead of re-parsing on every request.
 * {@code exclusiveTier} is true for E-J, redeemable once per restaurant per calendar month.
 */
@Entity
@Table(name = "promo_codes")
public class PromoCode {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "campaign_id", nullable = false)
    private Long campaignId;

    @Column(name = "code", nullable = false, unique = true, length = 7)
    private String code;

    @Column(name = "client_target", nullable = false)
    private int clientTarget;

    @Column(name = "duration_days", nullable = false)
    private int durationDays;

    @Column(name = "tier_letter", nullable = false, length = 1)
    private String tierLetter;

    @Column(name = "exclusive_tier", nullable = false)
    private boolean exclusiveTier = false;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

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

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public int getClientTarget() {
        return clientTarget;
    }

    public void setClientTarget(int clientTarget) {
        this.clientTarget = clientTarget;
    }

    public int getDurationDays() {
        return durationDays;
    }

    public void setDurationDays(int durationDays) {
        this.durationDays = durationDays;
    }

    public String getTierLetter() {
        return tierLetter;
    }

    public void setTierLetter(String tierLetter) {
        this.tierLetter = tierLetter;
    }

    public boolean isExclusiveTier() {
        return exclusiveTier;
    }

    public void setExclusiveTier(boolean exclusiveTier) {
        this.exclusiveTier = exclusiveTier;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}