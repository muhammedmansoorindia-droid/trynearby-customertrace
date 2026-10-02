package com.customertrace.domain;

/** Booking lifecycle. A booking is a collaboration/visit request, distinct from a campaign. */
public enum BookingStatus {
    REQUESTED,
    CONFIRMED,
    COMPLETED,
    CANCELLED,
    DECLINED;

    public boolean isTerminal() {
        return this == COMPLETED || this == CANCELLED || this == DECLINED;
    }
}