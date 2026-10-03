package com.customertrace.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import com.customertrace.domain.BookingStatus;
import com.customertrace.domain.Creator;
import com.customertrace.domain.Restaurant;
import com.customertrace.domain.User;
import com.customertrace.repository.BookingRepository;
import com.customertrace.repository.BookingStatusHistoryRepository;
import com.customertrace.support.IntegrationTestBase;

/**
 * Booking creation, visibility and status transitions.
 *
 * <p>The invariants protected here: bookings never cross a tenant boundary, only permitted
 * transitions succeed, and every accepted transition leaves a history row behind.
 */
class BookingIntegrationTest extends IntegrationTestBase {

    @Autowired
    private BookingRepository bookings;

    @Autowired
    private BookingStatusHistoryRepository history;

    private User admin;
    private Restaurant kitchen;
    private Restaurant rival;
    private Creator star;
    private User starUser;
    private User kitchenUser;
    private User rivalUser;

    @BeforeEach
    void setUp() {
        admin = createUser("admin-book@Trynearby", "ADMIN", "Admin");
        kitchen = createRestaurant("kitchen-book@restrnt", "Kitchen", "Kochi");
        rival = createRestaurant("rival-book@restrnt", "Rival", "Kochi");
        createCreator("star-book@creator", "Star", "@star-book");
        starUser = userRepository.findByUserIdIgnoreCase("star-book@creator").orElseThrow();
        kitchenUser = userRepository.findByUserIdIgnoreCase("kitchen-book@restrnt").orElseThrow();
        rivalUser = userRepository.findByUserIdIgnoreCase("rival-book@restrnt").orElseThrow();
        star = creatorRepository.findAll().stream()
                .filter(c -> "@star-book".equals(c.getHandle())).findFirst().orElseThrow();
    }

    private String bookingBody(Long restaurantId, int partySize) {
        return "{\"restaurantId\":" + restaurantId + ",\"visitDate\":\""
                + LocalDate.now().plusDays(7) + "\",\"partySize\":" + partySize
                + ",\"note\":\"Window table\"}";
    }

    /** Creates a booking and returns its id. */
    private String createBookingAt(Restaurant restaurant) throws Exception {
        return parse(mockMvc.perform(withToken(post("/api/creator/bookings"), tokenFor(starUser))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bookingBody(restaurant.getId(), 2)))
                .andExpect(status().isCreated()).andReturn()).path("id").asText();
    }

    @Test
    @DisplayName("a creator requests a booking and it starts as REQUESTED")
    void creatorCreatesBooking() throws Exception {
        mockMvc.perform(withToken(post("/api/creator/bookings"), tokenFor(starUser))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bookingBody(kitchen.getId(), 4)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("REQUESTED"))
                .andExpect(jsonPath("$.partySize").value(4))
                .andExpect(jsonPath("$.creatorId").value(star.getId()))
                .andExpect(jsonPath("$.restaurantId").value(kitchen.getId()));
        flush();

        assertThat(bookings.findByRestaurantIdOrderByVisitDateDesc(kitchen.getId())).hasSize(1);
    }

    @Test
    @DisplayName("the initial request is recorded in the status history")
    void creationWritesHistory() throws Exception {
        String id = createBookingAt(kitchen);
        flush();

        var rows = history.findByBookingIdOrderByChangedAtAsc(Long.valueOf(id));
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getToStatus()).isEqualTo(BookingStatus.REQUESTED);
        assertThat(rows.get(0).getFromStatus()).isNull();
    }

    @Test
    @DisplayName("a past visit date and an invalid party size are rejected")
    void invalidInputRejected() throws Exception {
        mockMvc.perform(withToken(post("/api/creator/bookings"), tokenFor(starUser))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"restaurantId\":" + kitchen.getId()
                                + ",\"visitDate\":\"" + LocalDate.now().minusDays(1)
                                + "\",\"partySize\":2}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VISIT_DATE_PAST"));

        mockMvc.perform(withToken(post("/api/creator/bookings"), tokenFor(starUser))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bookingBody(kitchen.getId(), 0)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARTY_SIZE"));

        mockMvc.perform(withToken(post("/api/creator/bookings"), tokenFor(starUser))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bookingBody(kitchen.getId(), 999)))
                .andExpect(status().isBadRequest());
        flush();
        // Nothing was written by any of the rejected requests.
        assertThat(bookings.count()).isZero();
    }

    @Test
    @DisplayName("booking an unknown restaurant is a 404")
    void unknownRestaurant() throws Exception {
        mockMvc.perform(withToken(post("/api/creator/bookings"), tokenFor(starUser))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bookingBody(999999L, 2)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("a restaurant cannot create bookings")
    void restaurantCannotCreate() throws Exception {
        mockMvc.perform(withToken(post("/api/creator/bookings"), tokenFor(kitchenUser))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bookingBody(kitchen.getId(), 2)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("each party sees only its own bookings")
    void listingsAreTenantScoped() throws Exception {
        createBookingAt(kitchen);
        createBookingAt(rival);
        flush();

        mockMvc.perform(withToken(get("/api/creator/bookings"), tokenFor(starUser)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2));

        mockMvc.perform(withToken(get("/api/restaurant/bookings"), tokenFor(kitchenUser)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].restaurantId").value(kitchen.getId()));

        mockMvc.perform(withToken(get("/api/restaurant/bookings"), tokenFor(rivalUser)))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].restaurantId").value(rival.getId()));

        mockMvc.perform(withToken(get("/api/admin/bookings"), tokenFor(admin)))
                .andExpect(jsonPath("$.total").value(2));
    }

    @Test
    @DisplayName("another restaurant cannot open a booking that is not its own")
    void crossRestaurantDetailDenied() throws Exception {
        String id = createBookingAt(kitchen);
        flush();

        mockMvc.perform(withToken(get("/api/restaurant/bookings/" + id),
                        tokenFor(rivalUser)))
                .andExpect(status().isNotFound());
        // The admin route is closed to restaurants entirely.
        mockMvc.perform(withToken(get("/api/admin/bookings/" + id), tokenFor(rivalUser)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("a restaurant confirms and then completes a booking")
    void restaurantConfirmsAndCompletes() throws Exception {
        String id = createBookingAt(kitchen);
        flush();

        mockMvc.perform(withToken(patch("/api/restaurant/bookings/" + id + "/status"),
                        tokenFor(kitchenUser)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"CONFIRMED\",\"reason\":\"Table booked\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"));

        mockMvc.perform(withToken(patch("/api/restaurant/bookings/" + id + "/status"),
                        tokenFor(kitchenUser)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"COMPLETED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));
        flush();

        var rows = history.findByBookingIdOrderByChangedAtAsc(Long.valueOf(id));
        assertThat(rows).hasSize(3);
        assertThat(rows.get(1).getFromStatus()).isEqualTo(BookingStatus.REQUESTED);
        assertThat(rows.get(1).getToStatus()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(rows.get(1).getReason()).isEqualTo("Table booked");
    }

    @Test
    @DisplayName("a creator may cancel its own request but not confirm it")
    void creatorCancelOnly() throws Exception {
        String id = createBookingAt(kitchen);
        flush();

        // Confirming is the restaurant's decision.
        mockMvc.perform(withToken(patch("/api/creator/bookings/" + id + "/status"),
                        tokenFor(starUser)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"CONFIRMED\"}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(withToken(patch("/api/creator/bookings/" + id + "/status"),
                        tokenFor(starUser)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"CANCELLED\",\"reason\":\"Plans changed\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    @Test
    @DisplayName("a restaurant cannot cancel a booking")
    void restaurantCannotCancel() throws Exception {
        String id = createBookingAt(kitchen);
        flush();

        mockMvc.perform(withToken(patch("/api/restaurant/bookings/" + id + "/status"),
                        tokenFor(kitchenUser)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"CANCELLED\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("a terminal booking cannot be reopened")
    void terminalStatesAreFinal() throws Exception {
        String id = createBookingAt(kitchen);
        flush();

        mockMvc.perform(withToken(patch("/api/restaurant/bookings/" + id + "/status"),
                        tokenFor(kitchenUser)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"DECLINED\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(withToken(patch("/api/restaurant/bookings/" + id + "/status"),
                        tokenFor(kitchenUser)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"CONFIRMED\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATUS_TRANSITION"));
        flush();

        assertThat(bookings.findById(Long.valueOf(id)).orElseThrow().getStatus())
                .isEqualTo(BookingStatus.DECLINED);
        // The refused transition wrote no history row.
        assertThat(history.findByBookingIdOrderByChangedAtAsc(Long.valueOf(id))).hasSize(2);
    }

    @Test
    @DisplayName("REQUESTED cannot jump straight to COMPLETED")
    void skippedTransitionRefused() throws Exception {
        String id = createBookingAt(kitchen);
        flush();

        mockMvc.perform(withToken(patch("/api/restaurant/bookings/" + id + "/status"),
                        tokenFor(kitchenUser)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"COMPLETED\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATUS_TRANSITION"));
    }

    @Test
    @DisplayName("an unknown status and an unknown booking are handled")
    void unknownStatusAndBooking() throws Exception {
        String id = createBookingAt(kitchen);
        flush();

        mockMvc.perform(withToken(patch("/api/restaurant/bookings/" + id + "/status"),
                        tokenFor(kitchenUser)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"TELEPORTED\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("UNKNOWN_STATUS"));

        mockMvc.perform(withToken(patch("/api/restaurant/bookings/" + id + "/status"),
                        tokenFor(kitchenUser)).contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("STATUS_REQUIRED"));

        mockMvc.perform(withToken(get("/api/restaurant/bookings/999999"),
                        tokenFor(kitchenUser)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("booking endpoints require authentication")
    void requiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/creator/bookings")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/restaurant/bookings")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/admin/bookings")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/creator/bookings")).andExpect(status().isUnauthorized());
    }
}