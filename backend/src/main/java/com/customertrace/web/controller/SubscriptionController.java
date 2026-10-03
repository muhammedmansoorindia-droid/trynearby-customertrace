package com.customertrace.web.controller;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.customertrace.security.CurrentUser;
import com.customertrace.service.SubscriptionService;
import com.customertrace.web.dto.SubscriptionDtos;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

/**
 * Subscription reads for restaurants and writes for administrators.
 *
 * <p>The split is deliberate: a restaurant may inspect and change its own plan, while status is
 * governed centrally. Writes are additionally protected by route-level role rules.
 */
@RestController
@RequestMapping("/api")
@Tag(name = "Subscriptions")
public class SubscriptionController {

    private final SubscriptionService subscriptions;

    public SubscriptionController(SubscriptionService subscriptions) {
        this.subscriptions = subscriptions;
    }

    @GetMapping("/restaurant/subscription")
    @Operation(summary = "The signed-in restaurant's current subscription")
    public ResponseEntity<SubscriptionDtos.SubscriptionResponse> current(
            @AuthenticationPrincipal CurrentUser actor) {
        return ResponseEntity.ok(subscriptions.current(actor));
    }

    @GetMapping("/restaurant/subscriptions")
    @Operation(summary = "Subscription history for the signed-in restaurant")
    public ResponseEntity<List<SubscriptionDtos.SubscriptionResponse>> history(
            @AuthenticationPrincipal CurrentUser actor) {
        return ResponseEntity.ok(subscriptions.history(actor));
    }

    @GetMapping("/plans")
    @Operation(summary = "Plans currently offered to restaurants")
    public ResponseEntity<List<SubscriptionDtos.PlanResponse>> plans() {
        return ResponseEntity.ok(subscriptions.activePlans());
    }

    @PutMapping("/admin/subscriptions/{id}/plan")
    @Operation(summary = "Change a subscription's plan (administrator)")
    public ResponseEntity<SubscriptionDtos.SubscriptionResponse> changePlan(
            @AuthenticationPrincipal CurrentUser actor, @PathVariable Long id,
            @Valid @RequestBody SubscriptionDtos.ChangePlanRequest request) {
        return ResponseEntity.ok(subscriptions.changePlan(actor, id, request.planId()));
    }

    @PatchMapping("/admin/subscriptions/{id}/status")
    @Operation(summary = "Change a subscription's status (administrator)")
    public ResponseEntity<SubscriptionDtos.SubscriptionResponse> changeStatus(
            @AuthenticationPrincipal CurrentUser actor, @PathVariable Long id,
            @Valid @RequestBody SubscriptionDtos.ChangeStatusRequest request) {
        return ResponseEntity.ok(subscriptions.changeStatus(actor, id, request.status()));
    }
}