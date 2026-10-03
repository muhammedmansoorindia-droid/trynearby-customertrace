package com.customertrace.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.customertrace.domain.Notification;
import com.customertrace.domain.User;
import com.customertrace.repository.NotificationRepository;
import com.customertrace.support.IntegrationTestBase;

/**
 * Notification listing, unread filtering and read state.
 *
 * <p>The central property under test is isolation: a user must never see, count or mark another
 * user's notification, even with a valid token for their own account.
 */
class NotificationIntegrationTest extends IntegrationTestBase {

    @Autowired
    private NotificationRepository notifications;

    private User alice;
    private User bob;
    private Long aliceNotificationId;

    @BeforeEach
    void setUp() {
        alice = createUser("alice-notify@restrnt", "RESTAURANT", "Alice");
        bob = createUser("bob-notify@restrnt", "RESTAURANT", "Bob");
        aliceNotificationId = seed(alice, "Alice first notification", false);
        seed(alice, "Alice second notification", false);
        seed(bob, "Bob notification", false);
        flush();
    }

    private Long seed(User recipient, String title, boolean read) {
        Notification n = new Notification();
        n.setType("TEST_EVENT");
        n.setAudience("RESTAURANT");
        n.setRecipientId(recipient.getId());
        n.setTitle(title);
        n.setBody("body for " + title);
        n.setLink("/notifications");
        n.setRead(read);
        n.setCreatedAt(Instant.now());
        return notifications.save(n).getId();
    }

    @Test
    @DisplayName("a user sees only their own notifications")
    void listIsScopedToTheAuthenticatedUser() throws Exception {
        mockMvc.perform(withToken(get("/api/notifications"), tokenFor(alice)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.total").value(2));
    }

    @Test
    @DisplayName("unreadCount counts only the caller's unread notifications")
    void unreadCountIsScoped() throws Exception {
        mockMvc.perform(withToken(get("/api/notifications/unread-count"), tokenFor(alice)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unreadCount").value(2));

        mockMvc.perform(withToken(get("/api/notifications/unread-count"), tokenFor(bob)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unreadCount").value(1));
    }

    @Test
    @DisplayName("unreadOnly filters to unread rows")
    void unreadOnlyFilter() throws Exception {
        mockMvc.perform(withToken(post("/api/notifications/read-all"), tokenFor(bob)))
                .andExpect(status().isOk());

        mockMvc.perform(withToken(get("/api/notifications").param("unreadOnly", "true"),
                        tokenFor(alice)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2));

        mockMvc.perform(withToken(get("/api/notifications").param("unreadOnly", "true"),
                        tokenFor(bob)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0));
    }

    @Test
    @DisplayName("marking read sets the flag and lowers the unread count")
    void markReadUpdatesState() throws Exception {
        mockMvc.perform(withToken(post("/api/notifications/" + aliceNotificationId + "/read"),
                        tokenFor(alice)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.read").value(true));

        mockMvc.perform(withToken(get("/api/notifications/unread-count"), tokenFor(alice)))
                .andExpect(jsonPath("$.unreadCount").value(1));
        flush();
        assertThat(notifications.findById(aliceNotificationId).orElseThrow().isRead()).isTrue();
    }

    @Test
    @DisplayName("another user cannot mark a notification they do not own")
    void markReadRejectsAnotherUsersNotification() throws Exception {
        mockMvc.perform(withToken(post("/api/notifications/" + aliceNotificationId + "/read"),
                        tokenFor(bob)))
                .andExpect(status().isNotFound());

        // Bob's own row must be untouched by the attempt.
        assertThat(notifications.findById(aliceNotificationId).orElseThrow().isRead()).isFalse();
    }

    @Test
    @DisplayName("marking an unknown notification returns 404")
    void markUnknownNotification() throws Exception {
        mockMvc.perform(withToken(post("/api/notifications/999999/read"), tokenFor(alice)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("markAllRead only clears the caller's notifications")
    void markAllReadIsScoped() throws Exception {
        mockMvc.perform(withToken(post("/api/notifications/read-all"), tokenFor(alice)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.updated").value(2));
        flush();

        assertThat(notifications.countUnread(alice.getId())).isZero();
        assertThat(notifications.countUnread(bob.getId())).isEqualTo(1);
    }

    @Test
    @DisplayName("listing notifications requires authentication")
    void listRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/notifications")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/notifications/read-all")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/notifications/1/read")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("an empty inbox returns an empty page rather than an error")
    void emptyInbox() throws Exception {
        User carol = createUser("carol-notify@restrnt", "RESTAURANT", "Carol");
        mockMvc.perform(withToken(get("/api/notifications"), tokenFor(carol)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0))
                .andExpect(jsonPath("$.total").value(0))
                .andExpect(jsonPath("$.unreadCount").value(0));
    }
}