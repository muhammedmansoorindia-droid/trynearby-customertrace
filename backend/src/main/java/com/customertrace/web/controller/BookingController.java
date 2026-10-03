package com.customertrace.web.controller;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.customertrace.domain.Booking;
import com.customertrace.domain.BookingStatus;
import com.customertrace.exception.ApiErrors;
import com.customertrace.security.CurrentUser;
import com.customertrace.service.BookingService;
import com.customertrace.web.dto.BookingDtos;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Bookings, in the namespace of whoever may read them.
 *
 * <p>Every route resolves the caller's own scope, so a creator listing at
 * {@code /api/creator/bookings} and a restaurant listing at {@code /api/restaurant/bookings} see
 * different, correct data without any extra query parameter.
 */
@RestController
@RequestMapping("/api")
@Tag(name = "Bookings")
public class BookingController {

    private final BookingService bookings;

    public BookingController(BookingService bookings) {
        this.bookings = bookings;
    }

    @GetMapping("/creator/bookings")
    @Operation(summary = "Bookings made by the signed-in creator")
    public ResponseEntity<BookingDtos.BookingPageResponse> forCreator(
            @AuthenticationPrincipal CurrentUser actor,
            @RequestParam(required = false) BookingStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(page(bookings.list(actor, status, page, size), page, size));
    }

    @GetMapping("/restaurant/bookings")
    @Operation(summary = "Bookings addressed to the signed-in restaurant")
    public ResponseEntity<BookingDtos.BookingPageResponse> forRestaurant(
            @AuthenticationPrincipal CurrentUser actor,
            @RequestParam(required = false) BookingStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(page(bookings.list(actor, status, page, size), page, size));
    }

    @GetMapping("/admin/bookings")
    @Operation(summary = "All bookings (administrator)")
    public ResponseEntity<BookingDtos.BookingPageResponse> all(
            @AuthenticationPrincipal CurrentUser actor,
            @RequestParam(required = false) BookingStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(page(bookings.list(actor, status, page, size), page, size));
    }

    private BookingDtos.BookingPageResponse page(Page<Booking> result, int page, int size) {
        List<BookingDtos.BookingResponse> items = result.getContent().stream()
                .map(bookings::toResponse).toList();
        return new BookingDtos.BookingPageResponse(items, result.getTotalElements(),
                Math.max(page, 0), Math.min(Math.max(size, 1), 100));
    }

    @GetMapping({"/creator/bookings/{id}", "/restaurant/bookings/{id}", "/admin/bookings/{id}"})
    @Operation(summary = "One booking with its status history")
    public ResponseEntity<BookingDtos.BookingDetailResponse> detail(
            @AuthenticationPrincipal CurrentUser actor, @PathVariable Long id) {
        return ResponseEntity.ok(bookings.detail(actor, id));
    }

    @PostMapping("/creator/bookings")
    @Operation(summary = "Request a booking at a restaurant")
    public ResponseEntity<BookingDtos.BookingResponse> create(
            @AuthenticationPrincipal CurrentUser actor,
            @RequestBody BookingDtos.CreateBookingRequest request) {
        return ResponseEntity.status(201).body(bookings.create(actor, request));
    }

    @PatchMapping({"/creator/bookings/{id}/status", "/restaurant/bookings/{id}/status",
            "/admin/bookings/{id}/status"})
    @Operation(summary = "Change a booking's status (permitted transitions only)")
    public ResponseEntity<BookingDtos.BookingResponse> changeStatus(
            @AuthenticationPrincipal CurrentUser actor, @PathVariable Long id,
            @RequestBody BookingDtos.ChangeStatusRequest request) {
        if (request.status() == null || request.status().isBlank()) {
            throw ApiErrors.badRequest("STATUS_REQUIRED", "A status is required.");
        }
        BookingStatus target;
        try {
            target = BookingStatus.valueOf(request.status().trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw ApiErrors.badRequest("UNKNOWN_STATUS",
                    "Unknown booking status: " + request.status());
        }
        return ResponseEntity.ok(bookings.changeStatus(actor, id, target, request.reason()));
    }
}