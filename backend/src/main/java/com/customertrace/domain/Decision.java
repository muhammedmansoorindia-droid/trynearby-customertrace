package com.customertrace.domain;

/**
 * A single party's approve/reject decision on a campaign.
 *
 * <p>Recorded separately per party so the creator can see both states and any reason given.
 */
public enum Decision {
    APPROVED,
    REJECTED
}