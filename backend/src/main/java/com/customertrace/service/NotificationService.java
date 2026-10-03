package com.customertrace.service;

import java.time.Instant;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.customertrace.domain.Notification;
import com.customertrace.exception.ApiErrors;
import com.customertrace.repository.NotificationRepository;
import com.customertrace.security.CurrentUser;

/**
 * Read and mark-read operations for the signed-in user's notifications.
 *
 * <p>Every query is keyed on the recipient id taken from the access token, never from a
 * request parameter, so one user cannot read or alter another's notifications even by guessing
 * an id. Creating notifications stays with the services that own each business event.
 */
@Service
public class NotificationService {

    private static final int MAX_PAGE_SIZE = 100;

    private final NotificationRepository notifications;

    public NotificationService(NotificationRepository notifications) {
        this.notifications = notifications;
    }

    /**
     * One page of notifications, newest first.
     *
     * @param unreadOnly when true, only unread rows are returned
     */
    @Transactional(readOnly = true)
    public Page<Notification> list(CurrentUser actor, boolean unreadOnly, Pageable pageable) {
        PageRequest clamped = clamp(pageable);
        if (!unreadOnly) {
            return notifications.findByRecipientIdOrderByCreatedAtDesc(actor.userId(),
                    clamped);
        }
        // Unread pages are small by nature, so an in-memory slice keeps the ordering stable.
        var unread = notifications
                .findByRecipientIdAndReadFalseOrderByCreatedAtDesc(actor.userId());
        int from = (int) Math.min(clamped.getOffset(), unread.size());
        int to = (int) Math.min(from + clamped.getPageSize(), unread.size());
        return new PageImpl<>(
                unread.subList(from, to), clamped, unread.size());
    }

    private static PageRequest clamp(Pageable pageable) {
        int size = Math.min(Math.max(pageable.getPageSize(), 1), MAX_PAGE_SIZE);
        return PageRequest.of(
                Math.max(pageable.getPageNumber(), 0), size, pageable.getSort());
    }

    @Transactional(readOnly = true)
    public long unreadCount(CurrentUser actor) {
        return notifications.countUnread(actor.userId());
    }

    /** Marks one notification read. Returns 404 when it exists but belongs to someone else. */
    @Transactional
    public Notification markRead(CurrentUser actor, Long notificationId) {
        int updated = notifications.markRead(actor.userId(), notificationId);
        if (updated == 0) {
            // Distinguish "no such row" from "not yours" only in the log; the caller gets 404
            // either way so existence cannot be probed.
            throw ApiErrors.notFound("Notification " + notificationId + " was not found.");
        }
        return notifications.findById(notificationId).orElseThrow(
                () -> ApiErrors.notFound("Notification " + notificationId + " was not found."));
    }

    @Transactional
    public long markAllRead(CurrentUser actor) {
        return notifications.markAllRead(actor.userId());
    }
}