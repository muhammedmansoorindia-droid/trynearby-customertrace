package com.customertrace.service;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.customertrace.domain.Creator;
import com.customertrace.domain.Notification;
import com.customertrace.domain.Restaurant;
import com.customertrace.domain.RestaurantCreatorAssignment;
import com.customertrace.exception.ApiErrors;
import com.customertrace.repository.CreatorRepository;
import com.customertrace.repository.NotificationRepository;
import com.customertrace.repository.RestaurantCreatorAssignmentRepository;
import com.customertrace.repository.RestaurantRepository;
import com.customertrace.security.CurrentUser;
import com.customertrace.web.dto.AssignmentDtos;

/**
 * Which creators are allowed to run campaigns for which restaurants.
 *
 * <p>Authorization rule: an administrator may assign anyone; a restaurant may assign to itself.
 * A creator has no write access at all — it is the subject of an assignment, not a manager of
 * one. Reads are always scoped to the caller's own restaurant or creator profile, so neither side
 * can enumerate the other's relationships.
 */
@Service
public class AssignmentService {

    private final RestaurantCreatorAssignmentRepository assignments;
    private final RestaurantRepository restaurants;
    private final CreatorRepository creators;
    private final NotificationRepository notifications;

    public AssignmentService(RestaurantCreatorAssignmentRepository assignments,
                             RestaurantRepository restaurants,
                             CreatorRepository creators,
                             NotificationRepository notifications) {
        this.assignments = assignments;
        this.restaurants = restaurants;
        this.creators = creators;
        this.notifications = notifications;
    }

    // ── Reads ───────────────────────────────────────────────────────────────

    /** Creators assigned to the signed-in restaurant. */
    @Transactional(readOnly = true)
    public List<AssignmentDtos.AssignmentResponse> forRestaurant(CurrentUser actor) {
        Restaurant restaurant = ownRestaurant(actor);
        return respond(assignments.findByRestaurantIdOrderByCreatedAtDesc(restaurant.getId()));
    }

    /** Restaurants the signed-in creator is assigned to. */
    @Transactional(readOnly = true)
    public List<AssignmentDtos.AssignmentResponse> forCreator(CurrentUser actor) {
        Creator creator = ownCreator(actor);
        // Names are resolved here too, so the creator's wizard can name each venue rather than
        // showing a bare numeric id.
        return respond(assignments.findByCreatorIdOrderByCreatedAtDesc(creator.getId()));
    }

    /** Every assignment on the platform, for administrators. */
    @Transactional(readOnly = true)
    public List<AssignmentDtos.AssignmentResponse> all() {
        return respond(assignments.findAll());
    }

    /**
     * True when this creator may create a campaign for this restaurant.
     *
     * <p>Administrators may act for any restaurant; otherwise an active assignment is required.
     */
    @Transactional(readOnly = true)
    public boolean mayActFor(CurrentUser actor, Long restaurantId) {
        if (actor.isAdmin()) {
            return true;
        }
        if (!actor.isCreator()) {
            return false;
        }
        Long creatorId = ownCreator(actor).getId();
        return assignments.findActiveCreatorIds(restaurantId).contains(creatorId);
    }

    /** The signed-in restaurant's own id, used where a request must not supply one. */
    public Long ownRestaurantId(CurrentUser actor) {
        return ownRestaurant(actor).getId();
    }

    // ── Writes ──────────────────────────────────────────────────────────────

    /**
     * Assigns a creator to a restaurant, or reactivates an existing row.
     *
     * <p>A duplicate pair is not an error: an inactive row is reactivated so the history of who
     * assigned it is preserved rather than duplicated.
     */
    @Transactional
    public AssignmentDtos.AssignmentResponse assign(CurrentUser actor, Long restaurantId,
                                                   Long creatorId) {
        Restaurant restaurant = requireAssignableRestaurant(actor, restaurantId);
        Creator creator = creators.findById(creatorId).orElseThrow(
                () -> ApiErrors.notFound("Creator " + creatorId + " was not found."));

        RestaurantCreatorAssignment assignment = assignments
                .findByRestaurantIdAndCreatorId(restaurant.getId(), creator.getId())
                .orElseGet(RestaurantCreatorAssignment::new);

        if (assignment.getId() != null && assignment.isActive()) {
            throw ApiErrors.conflict("ASSIGNMENT_EXISTS",
                    "That creator is already assigned to this restaurant.");
        }

        assignment.setRestaurantId(restaurant.getId());
        assignment.setCreatorId(creator.getId());
        assignment.setAssignedBy(actor.userId());
        assignment.setActive(true);
        assignment.setUpdatedAt(Instant.now());
        assignments.save(assignment);

        notifyCreator(creator, "New restaurant assignment",
                "You can now run campaigns for " + restaurant.getName() + ".");
        notifyRestaurant(restaurant, "Creator assigned",
                "Creator " + creator.getDisplayName() + " is now assigned to your restaurant.");
        return toResponse(assignment);
    }

    /**
     * Deactivates an assignment.
     *
     * <p>Deactivating an already-inactive assignment is reported as a conflict so a double click
     * does not look like a silent success, and no duplicate notification is sent.
     */
    @Transactional
    public AssignmentDtos.AssignmentResponse deactivate(CurrentUser actor, Long assignmentId) {
        RestaurantCreatorAssignment assignment = requireManageable(actor, assignmentId);
        if (!assignment.isActive()) {
            throw ApiErrors.conflict("ASSIGNMENT_INACTIVE",
                    "That assignment has already been deactivated.");
        }

        assignment.setActive(false);
        assignment.setUpdatedAt(Instant.now());
        assignments.save(assignment);

        notifyCreator(assignment, "Restaurant assignment ended",
                "Your assignment to this restaurant has ended.");
        return toResponse(assignment);
    }

    /** Reactivates a previously deactivated assignment. */
    @Transactional
    public AssignmentDtos.AssignmentResponse reactivate(CurrentUser actor, Long assignmentId) {
        RestaurantCreatorAssignment assignment = requireManageable(actor, assignmentId);
        if (assignment.isActive()) {
            throw ApiErrors.conflict("ASSIGNMENT_ACTIVE",
                    "That assignment is already active.");
        }

        assignment.setActive(true);
        assignment.setAssignedBy(actor.userId());
        assignment.setUpdatedAt(Instant.now());
        assignments.save(assignment);

        notifyCreator(assignment, "Restaurant assignment restored",
                "Your assignment to this restaurant is active again.");
        return toResponse(assignment);
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private Restaurant ownRestaurant(CurrentUser actor) {
        return restaurants.findByUserId(actor.userId()).orElseThrow(
                () -> ApiErrors.forbidden("This endpoint requires a restaurant account."));
    }

    private Creator ownCreator(CurrentUser actor) {
        return creators.findByUserId(actor.userId()).orElseThrow(
                () -> ApiErrors.forbidden("This endpoint requires a creator account."));
    }

    /** An administrator may target any restaurant; a restaurant may only target itself. */
    private Restaurant requireAssignableRestaurant(CurrentUser actor, Long restaurantId) {
        if (actor.isAdmin()) {
            return restaurants.findById(restaurantId).orElseThrow(
                    () -> ApiErrors.notFound("Restaurant " + restaurantId + " was not found."));
        }
        Restaurant own = ownRestaurant(actor);
        if (!own.getId().equals(restaurantId)) {
            // 404 so another tenant's restaurant ids cannot be probed.
            throw ApiErrors.notFound("Restaurant " + restaurantId + " was not found.");
        }
        return own;
    }

    /**
     * Loads an assignment the caller may change.
     *
     * <p>Administrators may change any; a restaurant may change only its own.
     */
    private RestaurantCreatorAssignment requireManageable(CurrentUser actor,
                                                           Long assignmentId) {
        RestaurantCreatorAssignment assignment = assignments.findById(assignmentId)
                .orElseThrow(() -> ApiErrors.notFound(
                        "Assignment " + assignmentId + " was not found."));
        if (actor.isAdmin()) {
            return assignment;
        }
        if (!actor.isRestaurant()) {
            throw ApiErrors.forbidden("Only administrators and restaurants may change "
                    + "assignments.");
        }
        Restaurant own = ownRestaurant(actor);
        if (!assignment.getRestaurantId().equals(own.getId())) {
            throw ApiErrors.notFound("Assignment " + assignmentId + " was not found.");
        }
        return assignment;
    }

    private void notifyCreator(RestaurantCreatorAssignment assignment, String title, String body) {
        creators.findById(assignment.getCreatorId()).ifPresent(c -> save(c.getUserId(),
                "CREATOR", title, body, "/creator/restaurants"));
    }

    private void notifyCreator(Creator creator, String title, String body) {
        save(creator.getUserId(), "CREATOR", title, body, "/creator/restaurants");
    }

    private void notifyRestaurant(Restaurant restaurant, String title, String body) {
        save(restaurant.getUserId(), "RESTAURANT", title, body, "/restaurant/creators");
    }

    private void save(Long recipientId, String audience, String title, String body, String link) {
        Notification n = new Notification();
        n.setType("ASSIGNMENT");
        n.setAudience(audience);
        n.setRecipientId(recipientId);
        n.setTitle(title);
        n.setBody(body);
        n.setLink(link);
        n.setCreatedAt(Instant.now());
        notifications.save(n);
    }

    /**
     * Maps rows with their display names resolved in one pass each.
     *
     * <p>The names are looked up in bulk rather than per row so a long assignment history does not
     * turn the admin list into an N+1 query storm.
     */
    private List<AssignmentDtos.AssignmentResponse> respond(
            List<RestaurantCreatorAssignment> rows) {
        Map<Long, String> restaurantNames = restaurants.findAll().stream()
                .collect(Collectors.toMap(Restaurant::getId, Restaurant::getName));
        Map<Long, String> creatorNames = creators.findAll().stream()
                .collect(Collectors.toMap(Creator::getId, Creator::getDisplayName));

        return rows.stream()
                .map(a -> toResponse(a,
                        restaurantNames.get(a.getRestaurantId()),
                        creatorNames.get(a.getCreatorId())))
                .toList();
    }

    private AssignmentDtos.AssignmentResponse toResponse(RestaurantCreatorAssignment a) {
        return toResponse(a, null, null);
    }

    private AssignmentDtos.AssignmentResponse toResponse(RestaurantCreatorAssignment a,
                                                          String restaurantName,
                                                          String creatorName) {
        return new AssignmentDtos.AssignmentResponse(a.getId(), a.getRestaurantId(),
                a.getCreatorId(), a.isActive(), a.getAssignedBy(), a.getCreatedAt(),
                a.getUpdatedAt(), restaurantName, creatorName);
    }
}