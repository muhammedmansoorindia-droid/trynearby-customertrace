package com.customertrace.web.controller;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.customertrace.security.CurrentUser;
import com.customertrace.service.AdminDirectoryService;
import com.customertrace.web.dto.AssignmentDtos;
import com.customertrace.web.dto.SubscriptionDtos;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

/**
 * Platform directory: restaurants, creators and their subscriptions.
 *
 * <p>Whole-platform views and profile status changes. Every route sits under {@code /api/admin} so
 * the existing {@code hasRole("ADMIN")} rule in {@code SecurityConfig} governs it; the service adds
 * no second authorization decision of its own, because an administrator manages everything here.
 */
@RestController
@RequestMapping("/api/admin")
@Tag(name = "Administration")
public class AdminDirectoryController {

    private final AdminDirectoryService directory;

    public AdminDirectoryController(AdminDirectoryService directory) {
        this.directory = directory;
    }

    @GetMapping("/restaurants")
    @Operation(summary = "Every restaurant with status, subscription and campaign counts")
    public ResponseEntity<List<AssignmentDtos.RestaurantSummary>> restaurants() {
        return ResponseEntity.ok(directory.restaurants());
    }

    @PatchMapping("/restaurants/{id}/status")
    @Operation(summary = "Suspend or reactivate a restaurant profile")
    public ResponseEntity<AssignmentDtos.RestaurantSummary> setRestaurantStatus(
            @AuthenticationPrincipal CurrentUser actor, @PathVariable Long id,
            @Valid @RequestBody AssignmentDtos.StatusRequest request) {
        return ResponseEntity.ok(directory.setRestaurantStatus(id, request.status()));
    }

    @GetMapping("/creators")
    @Operation(summary = "Every creator with status and current workload")
    public ResponseEntity<List<AssignmentDtos.CreatorSummary>> creators() {
        return ResponseEntity.ok(directory.creators());
    }

    @PatchMapping("/creators/{id}/status")
    @Operation(summary = "Suspend or reactivate a creator profile")
    public ResponseEntity<AssignmentDtos.CreatorSummary> setCreatorStatus(
            @AuthenticationPrincipal CurrentUser actor, @PathVariable Long id,
            @Valid @RequestBody AssignmentDtos.StatusRequest request) {
        return ResponseEntity.ok(directory.setCreatorStatus(id, request.status()));
    }

    @GetMapping("/subscriptions")
    @Operation(summary = "Every subscription on the platform")
    public ResponseEntity<List<SubscriptionDtos.AdminSubscriptionResponse>> subscriptions() {
        return ResponseEntity.ok(directory.subscriptions());
    }
}