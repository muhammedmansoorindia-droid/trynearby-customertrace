package com.customertrace.web.dto;

import java.time.Instant;

/** Subscription payloads. No payment or card data is represented anywhere here. */
public final class SubscriptionDtos {

    private SubscriptionDtos() {
    }

    /** A restaurant's subscription with its plan and entitlement state. */
    public record SubscriptionResponse(
            Long id,
            Long restaurantId,
            String planCode,
            String planName,
            String status,
            String billingCycle,
            long amountPaise,
            boolean entitled,
            Instant startedAt,
            Instant renewsAt,
            Instant cancelledAt,
            Instant updatedAt) {
    }

    /** A purchasable plan offered to restaurants. */
    public record PlanResponse(
            Long id,
            String code,
            String name,
            long monthlyPricePaise,
            String features,
            int maxCampaigns) {
    }

    /** Body for an administrator changing a subscription's plan. */
    public record ChangePlanRequest(Long planId) {
    }

    /** Body for an administrator changing a subscription's status. */
    public record ChangeStatusRequest(String status) {
    }
}