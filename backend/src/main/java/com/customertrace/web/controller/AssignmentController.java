package com.customertrace.web.controller;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.customertrace.security.CurrentUser;
import com.customertrace.service.AssignmentService;
import com.customertrace.web.dto.AssignmentDtos;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Creator-to-restaurant assignments.
 *
 * <p>Reads are per-tenant; writes live under the restaurant or admin namespaces so the security
 * rules alone already prevent a creator from granting itself access.
 */
@RestController
@RequestMapping("/api")
@Tag(name = "Assignments")
public class AssignmentController {

    private final AssignmentService assignments;

    public AssignmentController(AssignmentService assignments) {
        this.assignments = assignments;
    }

    @GetMapping("/restaurant/assignments")
    @Operation(summary = "Creators assigned to the signed-in restaurant")
    public ResponseEntity<List<AssignmentDtos.AssignmentResponse>> forRestaurant(
            @AuthenticationPrincipal CurrentUser actor) {
        return ResponseEntity.ok(assignments.forRestaurant(actor));
    }

    @GetMapping("/creator/assignments")
    @Operation(summary = "Restaurants the signed-in creator is assigned to")
    public ResponseEntity<List<AssignmentDtos.AssignmentResponse>> forCreator(
            @AuthenticationPrincipal CurrentUser actor) {
        return ResponseEntity.ok(assignments.forCreator(actor));
    }

    @GetMapping("/admin/assignments")
    @Operation(summary = "All assignments (administrator)")
    public ResponseEntity<List<AssignmentDtos.AssignmentResponse>> all() {
        return ResponseEntity.ok(assignments.all());
    }

    @PostMapping("/restaurant/assignments")
    @Operation(summary = "Assign a creator to the signed-in restaurant")
    public ResponseEntity<AssignmentDtos.AssignmentResponse> assignAsRestaurant(
            @AuthenticationPrincipal CurrentUser actor,
            @RequestBody AssignmentDtos.AssignRequest request) {
        // The restaurant may only ever target itself, so its id comes from the token.
        Long own = assignments.ownRestaurantId(actor);
        return ResponseEntity.status(201)
                .body(assignments.assign(actor, own, request.creatorId()));
    }

    @PostMapping("/admin/assignments")
    @Operation(summary = "Assign a creator to any restaurant (administrator)")
    public ResponseEntity<AssignmentDtos.AssignmentResponse> assignAsAdmin(
            @AuthenticationPrincipal CurrentUser actor,
            @RequestBody AssignmentDtos.AssignRequest request) {
        return ResponseEntity.status(201)
                .body(assignments.assign(actor, request.restaurantId(), request.creatorId()));
    }

    @DeleteMapping("/restaurant/assignments/{id}")
    @Operation(summary = "Deactivate one of the restaurant's assignments")
    public ResponseEntity<AssignmentDtos.AssignmentResponse> deactivate(
            @AuthenticationPrincipal CurrentUser actor, @PathVariable Long id) {
        return ResponseEntity.ok(assignments.deactivate(actor, id));
    }

    @PostMapping("/admin/assignments/{id}/reactivate")
    @Operation(summary = "Reactivate a deactivated assignment (administrator)")
    public ResponseEntity<AssignmentDtos.AssignmentResponse> reactivate(
            @AuthenticationPrincipal CurrentUser actor, @PathVariable Long id) {
        return ResponseEntity.ok(assignments.reactivate(actor, id));
    }

    @DeleteMapping("/admin/assignments/{id}")
    @Operation(summary = "Deactivate any assignment (administrator)")
    public ResponseEntity<AssignmentDtos.AssignmentResponse> deactivateAsAdmin(
            @AuthenticationPrincipal CurrentUser actor, @PathVariable Long id) {
        return ResponseEntity.ok(assignments.deactivate(actor, id));
    }
}