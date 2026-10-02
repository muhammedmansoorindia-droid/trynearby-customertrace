package com.customertrace.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A restaurant's plan on the platform.
 *
 * <p>Reference data only — there is no payment integration, so a plan carries no card or
 * provider details. Actual money is held on the {@link Subscription} that references it.
 */
@Entity
@Table(name = "subscription_plans")
public class SubscriptionPlan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "code", nullable = false, unique = true, length = 32)
    private String code;

    @Column(name = "name", nullable = false, length = 80)
    private String name;

    @Column(name = "monthly_price_paise", nullable = false)
    private long monthlyPricePaise;

    @Column(name = "max_campaigns", nullable = false)
    private int maxCampaigns;

    @Column(name = "features", length = 500)
    private String features;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public long getMonthlyPricePaise() {
        return monthlyPricePaise;
    }

    public void setMonthlyPricePaise(long monthlyPricePaise) {
        this.monthlyPricePaise = monthlyPricePaise;
    }

    public int getMaxCampaigns() {
        return maxCampaigns;
    }

    public void setMaxCampaigns(int maxCampaigns) {
        this.maxCampaigns = maxCampaigns;
    }

    public String getFeatures() {
        return features;
    }

    public void setFeatures(String features) {
        this.features = features;
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

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}