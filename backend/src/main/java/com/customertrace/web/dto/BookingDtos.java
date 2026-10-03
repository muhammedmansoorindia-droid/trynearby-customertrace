package com.customertrace.web.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;


/** Booking payloads. */
public final class BookingDtos {

    private BookingDtos() {
    }

    /** One booking. */
    public record BookingResponse(
            Long id,
            Long creatorId,
            Long restaurantId,
            LocalDate visitDate,
            int partySize,
            String status,
            String note,
            Instant createdAt,
            Instant updatedAt) {
    }

    /** One status-change audit row. */
    public record HistoryResponse(
            Long id,
            Long bookingId,
            String fromStatus,
            String toStatus,
            Long changedBy,
            String reason,
            Instant changedAt) {
    }

    /** A booking plus its full status history. */
    public record BookingDetailResponse(BookingResponse booking, List<HistoryResponse> history) {
    }

    /** A page of bookings. */
    public record BookingPageResponse(List<BookingResponse> items, long total, int page, int size) {
    }

    /** Body for creating a booking; the creator is taken from the token. */
    public record CreateBookingRequest(Long restaurantId, LocalDate visitDate, int partySize,
                                       String note) {
    }

    /** Body for changing a booking's status. */
    public record ChangeStatusRequest(String status, String reason) {
    }
}