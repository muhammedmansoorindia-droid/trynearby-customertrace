package com.customertrace.web.controller;

import java.time.Instant;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.customertrace.domain.Notification;
import com.customertrace.security.CurrentUser;
import com.customertrace.service.NotificationService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * In-app notifications for the signed-in user.
 *
 * <p>No route accepts a recipient id: the audience is always the authenticated principal.
 */
@RestController
@RequestMapping("/api/notifications")
@Tag(name = "Notifications")
public class NotificationController {

    private static final int DEFAULT_PAGE_SIZE = 20;

    private final NotificationService notifications;

    public NotificationController(NotificationService notifications) {
        this.notifications = notifications;
    }

    /** A page of notifications plus the caller's current unread total. */
    public record NotificationPageResponse(List<NotificationItem> items, long total,
                                           long unreadCount, int page, int size) {
    }

    public record NotificationItem(Long id, String type, String title, String body, String link,
                                   boolean read, Instant createdAt) {

        static NotificationItem from(Notification n) {
            return new NotificationItem(n.getId(), n.getType(), n.getTitle(), n.getBody(),
                    n.getLink(), n.isRead(), n.getCreatedAt());
        }
    }

    public record UnreadCountResponse(long unreadCount) {
    }

    public record MarkAllReadResponse(long updated) {
    }

    @GetMapping
    @Operation(summary = "Notifications for the signed-in user, newest first")
    public ResponseEntity<NotificationPageResponse> list(
            @AuthenticationPrincipal CurrentUser actor,
            @RequestParam(defaultValue = "false") boolean unreadOnly,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "" + DEFAULT_PAGE_SIZE) int size) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), 100);
        Page<Notification> pageResult = notifications.list(actor, unreadOnly,
                PageRequest.of(safePage, safeSize));
        return ResponseEntity.ok(new NotificationPageResponse(
                pageResult.getContent().stream().map(NotificationItem::from).toList(),
                pageResult.getTotalElements(),
                notifications.unreadCount(actor),
                safePage, safeSize));
    }

    @GetMapping("/unread-count")
    @Operation(summary = "Unread notification count for the signed-in user")
    public ResponseEntity<UnreadCountResponse> unreadCount(
            @AuthenticationPrincipal CurrentUser actor) {
        return ResponseEntity.ok(new UnreadCountResponse(notifications.unreadCount(actor)));
    }

    @PostMapping("/{id}/read")
    @Operation(summary = "Mark one of the caller's notifications as read")
    public ResponseEntity<NotificationItem> markRead(
            @AuthenticationPrincipal CurrentUser actor, @PathVariable Long id) {
        return ResponseEntity.ok(NotificationItem.from(notifications.markRead(actor, id)));
    }

    @PostMapping("/read-all")
    @Operation(summary = "Mark every notification of the caller as read")
    public ResponseEntity<MarkAllReadResponse> markAllRead(
            @AuthenticationPrincipal CurrentUser actor) {
        return ResponseEntity.ok(new MarkAllReadResponse(notifications.markAllRead(actor)));
    }
}