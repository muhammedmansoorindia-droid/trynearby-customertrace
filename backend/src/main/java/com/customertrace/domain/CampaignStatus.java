package com.customertrace.domain;

/**
 * Campaign lifecycle.
 *
 * <p>The dual-approval rule is encoded here: a campaign reaches {@link #ACTIVE} only when
 * {@code adminDecision == APPROVED} <em>and</em> {@code restaurantDecision == APPROVED}.
 * Any single rejection moves it to {@link #REJECTED}, and from there only an explicit
 * resubmission ({@link #PENDING}) may revive it.
 */
public enum CampaignStatus {
    /** Created but not yet submitted; editable and not reviewable. */
    DRAFT,
    /** Submitted, awaiting both decisions. */
    PENDING,
    /** Both Admin and Restaurant approved. The only redeemable state. */
    ACTIVE,
    /** At least one party rejected. Terminal until an explicit resubmission. */
    REJECTED,
    /** Withdrawn by the creator. */
    CANCELLED,
    /** Validity window elapsed. */
    COMPLETED;

    public boolean isRedeemable() {
        return this == ACTIVE;
    }
}