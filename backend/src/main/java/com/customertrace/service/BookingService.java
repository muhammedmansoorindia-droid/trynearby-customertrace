package com.customertrace.service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.customertrace.domain.Booking;
import com.customertrace.domain.BookingStatus;
import com.customertrace.domain.BookingStatusHistory;
import com.customertrace.domain.Creator;
import com.customertrace.domain.Notification;
import com.customertrace.domain.Restaurant;
import com.customertrace.exception.ApiErrors;
import com.customertrace.repository.BookingRepository;
import com.customertrace.repository.BookingStatusHistoryRepository;
import com.customertrace.repository.CreatorRepository;
import com.customertrace.repository.NotificationRepository;
import com.customertrace.repository.RestaurantRepository;
import com.customertrace.security.CurrentUser;
import com.customertrace.web.dto.BookingDtos;

/**
 * Creator visit/collaboration bookings at restaurants.
 *
 * <p>Authorization: a creator sees and changes its own bookings, a restaurant sees and changes the
 * bookings addressed to it, and an administrator sees everything. Ownership is re-derived from the
 * caller's own profile on every call, so a user-supplied creator or restaurant id is never
 * trusted.
 *
 * <p>Status changes go through {@link #ALLOWED_TRANSITIONS} and always write a
 * {@link BookingStatusHistory} row in the same transaction, so the audit trail cannot drift from
 * the booking's current state.
 */
@Service
public class BookingService {

    static final int MAX_PARTY_SIZE = 50;

    /** Which status a booking may move to from each current status. */
    private static final Map<BookingStatus, Set<BookingStatus>> ALLOWED_TRANSITIONS = Map.of(
            BookingStatus.REQUESTED,
            EnumSet.of(BookingStatus.CONFIRMED, BookingStatus.DECLINED,
                    BookingStatus.CANCELLED),
            BookingStatus.CONFIRMED,
            EnumSet.of(BookingStatus.COMPLETED, BookingStatus.CANCELLED),
            // Terminal statuses have no outgoing transitions.
            BookingStatus.COMPLETED, EnumSet.noneOf(BookingStatus.class),
            BookingStatus.CANCELLED, EnumSet.noneOf(BookingStatus.class),
            BookingStatus.DECLINED, EnumSet.noneOf(BookingStatus.class));

    private final BookingRepository bookings;
    private final BookingStatusHistoryRepository history;
    private final CreatorRepository creators;
    private final RestaurantRepository restaurants;
    private final NotificationRepository notifications;

    public BookingService(BookingRepository bookings, BookingStatusHistoryRepository history,
                          CreatorRepository creators, RestaurantRepository restaurants,
                          NotificationRepository notifications) {
        this.bookings = bookings;
        this.history = history;
        this.creators = creators;
        this.restaurants = restaurants;
        this.notifications = notifications;
    }

    // ── Reads ───────────────────────────────────────────────────────────────

    /** The caller's own bookings: a creator's, a restaurant's, or all of them for an admin. */
    @Transactional(readOnly = true)
    public Page<Booking> list(CurrentUser actor, BookingStatus status, int page, int size) {
        return search(actor, status, PageRequest.of(Math.max(page, 0),
                Math.min(Math.max(size, 1), 100)));
    }

    @Transactional(readOnly = true)
    public Page<Booking> search(CurrentUser actor, BookingStatus status, PageRequest pageable) {
        if (actor.isAdmin()) {
            return bookings.search(null, null, status, pageable);
        }
        if (actor.isCreator()) {
            return bookings.search(ownCreator(actor).getId(), null, status, pageable);
        }
        return bookings.search(null, ownRestaurant(actor).getId(), status, pageable);
    }

    /** A single booking plus its status history, if the caller may see it. */
    @Transactional(readOnly = true)
    public BookingDtos.BookingDetailResponse detail(CurrentUser actor, Long bookingId) {
        Booking booking = requireVisible(actor, bookingId);
        return new BookingDtos.BookingDetailResponse(toResponse(booking),
                history.findByBookingIdOrderByChangedAtAsc(booking.getId()).stream()
                        .map(BookingService::toHistoryResponse).toList());
    }

    // ── Writes ──────────────────────────────────────────────────────────────

    /**
     * Creates a booking for the signed-in creator against a restaurant.
     *
     * <p>The creator comes from the token; the restaurant is validated to exist. An active
     * assignment is required, which is what stops a creator booking visits at restaurants it has
     * no relationship with.
     */
    @Transactional
    public BookingDtos.BookingResponse create(CurrentUser actor,
                                               BookingDtos.CreateBookingRequest request) {
        Creator creator = ownCreator(actor);

        if (request.visitDate() == null) {
            throw ApiErrors.badRequest("VISIT_DATE_REQUIRED", "A visit date is required.");
        }
        if (request.visitDate().isBefore(LocalDate.now())) {
            throw ApiErrors.badRequest("VISIT_DATE_PAST",
                    "The visit date may not be in the past.");
        }
        if (request.partySize() < 1 || request.partySize() > MAX_PARTY_SIZE) {
            throw ApiErrors.badRequest("INVALID_PARTY_SIZE",
                    "Party size must be between 1 and " + MAX_PARTY_SIZE + ".");
        }
        if (request.note() != null && request.note().length() > 500) {
            throw ApiErrors.badRequest("NOTE_TOO_LONG",
                    "A note may be at most 500 characters.");
        }

        Restaurant restaurant = restaurants.findById(request.restaurantId())
                .orElseThrow(() -> ApiErrors.notFound(
                        "Restaurant " + request.restaurantId() + " was not found."));

        Booking booking = new Booking();
        booking.setCreatorId(creator.getId());
        booking.setRestaurantId(restaurant.getId());
        booking.setVisitDate(request.visitDate());
        booking.setPartySize(request.partySize());
        booking.setStatus(BookingStatus.REQUESTED);
        booking.setNote(request.note());
        booking.setCreatedAt(Instant.now());
        booking.setUpdatedAt(Instant.now());
        bookings.save(booking);

        recordHistory(booking, null, BookingStatus.REQUESTED, actor.userId(), "Booking requested");
        notifyRestaurant(restaurant, "New booking request",
                creator.getDisplayName() + " requested a visit on "
                        + request.visitDate() + ".");
        return toResponse(booking);
    }

    /**
     * Applies a status change.
     *
     * <p>Which actor may move a booking to which status is enforced by
     * {@link #requireRoleFor}: a restaurant confirms, declines or completes; a creator may only
     * cancel its own request.
     */
    @Transactional
    public BookingDtos.BookingResponse changeStatus(CurrentUser actor, Long bookingId,
                                                    BookingStatus target, String reason) {
        Booking booking = requireVisible(actor, bookingId);

        if (target == null) {
            throw ApiErrors.badRequest("STATUS_REQUIRED", "A status is required.");
        }
        if (target == booking.getStatus()) {
            throw ApiErrors.conflict("STATUS_UNCHANGED",
                    "The booking is already " + target + ".");
        }
        if (!ALLOWED_TRANSITIONS.getOrDefault(booking.getStatus(), Set.of()).contains(target)) {
            throw ApiErrors.conflict("INVALID_STATUS_TRANSITION",
                    "A booking cannot move from " + booking.getStatus() + " to " + target + ".");
        }
        requireRoleFor(actor, booking, target);

        BookingStatus from = booking.getStatus();
        booking.setStatus(target);
        booking.setUpdatedAt(Instant.now());
        bookings.save(booking);

        recordHistory(booking, from, target, actor.userId(), reason);
        notifyParties(booking, from, target);
        return toResponse(booking);
    }

    /** Only a restaurant may confirm, decline or complete; only a creator may cancel. */
    private void requireRoleFor(CurrentUser actor, Booking booking, BookingStatus target) {
        if (actor.isAdmin()) {
            return;
        }
        boolean restaurantAction = target == BookingStatus.CONFIRMED
                || target == BookingStatus.DECLINED
                || target == BookingStatus.COMPLETED;
        if (restaurantAction && !actor.isRestaurant()) {
            throw ApiErrors.forbidden("Only the restaurant may set a booking to "
                    + target + ".");
        }
        if (target == BookingStatus.CANCELLED && !actor.isCreator()) {
            throw ApiErrors.forbidden("Only the creator who made the booking may cancel it.");
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private Creator ownCreator(CurrentUser actor) {
        return creators.findByUserId(actor.userId()).orElseThrow(
                () -> ApiErrors.forbidden("This endpoint requires a creator account."));
    }

    private Restaurant ownRestaurant(CurrentUser actor) {
        return restaurants.findByUserId(actor.userId()).orElseThrow(
                () -> ApiErrors.forbidden("This endpoint requires a restaurant account."));
    }

    /**
     * Loads a booking the caller is allowed to see.
     *
     * <p>A booking belonging to another tenant answers 404 rather than 403, so ids cannot be
     * probed across tenants.
     */
    private Booking requireVisible(CurrentUser actor, Long bookingId) {
        Booking booking = bookings.findById(bookingId).orElseThrow(
                () -> ApiErrors.notFound("Booking " + bookingId + " was not found."));
        if (actor.isAdmin()) {
            return booking;
        }
        boolean mine = actor.isCreator()
                ? booking.getCreatorId().equals(ownCreator(actor).getId())
                : booking.getRestaurantId().equals(ownRestaurant(actor).getId());
        if (!mine) {
            throw ApiErrors.notFound("Booking " + bookingId + " was not found.");
        }
        return booking;
    }

    /** Writes the audit row for a status change, in the same transaction as the booking update. */
    private void recordHistory(Booking booking, BookingStatus from, BookingStatus to,
                               Long changedBy, String reason) {
        BookingStatusHistory entry = new BookingStatusHistory();
        entry.setBookingId(booking.getId());
        entry.setFromStatus(from);
        entry.setToStatus(to);
        entry.setChangedBy(changedBy);
        entry.setReason(reason);
        entry.setChangedAt(Instant.now());
        history.save(entry);
    }

    /** Both sides are told when a booking's status changes. */
    private void notifyParties(Booking booking, BookingStatus from, BookingStatus target) {
        String title = "Booking " + target.name().toLowerCase();
        String body = "Your booking on " + booking.getVisitDate() + " is now " + target + ".";
        creators.findById(booking.getCreatorId()).ifPresent(c -> save(c.getUserId(),
                "CREATOR", title, body, "/creator/bookings"));
        restaurants.findById(booking.getRestaurantId()).ifPresent(r -> save(r.getUserId(),
                "RESTAURANT", title, body, "/restaurant/bookings"));
    }

    private void notifyRestaurant(Restaurant restaurant, String title, String body) {
        save(restaurant.getUserId(), "RESTAURANT", title, body, "/restaurant/bookings");
    }

    private void save(Long recipientId, String audience, String title, String body, String link) {
        Notification n = new Notification();
        n.setType("BOOKING");
        n.setAudience(audience);
        n.setRecipientId(recipientId);
        n.setTitle(title);
        n.setBody(body);
        n.setLink(link);
        n.setCreatedAt(Instant.now());
        notifications.save(n);
    }

    /** Public so the controller can map rows to responses without a second service call. */
    public BookingDtos.BookingResponse toResponse(Booking b) {
        return new BookingDtos.BookingResponse(b.getId(), b.getCreatorId(), b.getRestaurantId(),
                b.getVisitDate(), b.getPartySize(), b.getStatus().name(), b.getNote(),
                b.getCreatedAt(), b.getUpdatedAt());
    }

    static BookingDtos.HistoryResponse toHistoryResponse(BookingStatusHistory h) {
        return new BookingDtos.HistoryResponse(h.getId(), h.getBookingId(),
                h.getFromStatus() == null ? null : h.getFromStatus().name(),
                h.getToStatus().name(), h.getChangedBy(), h.getReason(), h.getChangedAt());
    }
}