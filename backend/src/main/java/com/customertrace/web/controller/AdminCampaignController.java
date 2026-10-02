package com.customertrace.web.controller;

import java.util.List;

import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.customertrace.domain.CampaignStatus;
import com.customertrace.domain.Decision;
import com.customertrace.domain.DecisionParty;
import com.customertrace.security.CurrentUser;
import com.customertrace.service.CampaignQueryService;
import com.customertrace.service.CampaignWorkflowService;
import com.customertrace.web.dto.CampaignDtos;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

/**
 * TryNearby administrator campaign governance.
 *
 * <p>The deciding party is fixed to ADMIN by the route, so an admin cannot record the
 * restaurant's decision even by crafting the request body.
 */
@RestController
@RequestMapping("/api/admin/campaigns")
@Tag(name = "Admin campaigns")
public class AdminCampaignController {

    private final CampaignWorkflowService workflow;
    private final CampaignQueryService queries;

    public AdminCampaignController(CampaignWorkflowService workflow, CampaignQueryService queries) {
        this.workflow = workflow;
        this.queries = queries;
    }

    @GetMapping
    @Operation(summary = "All campaigns, optionally filtered by status")
    public ResponseEntity<List<CampaignDtos.CampaignResponse>> list(
            @RequestParam(required = false) CampaignStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return ResponseEntity.ok(queries.listAll(status, PageRequest.of(page, size)));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Campaign detail with both decision states")
    public ResponseEntity<CampaignDtos.CampaignResponse> get(
            @AuthenticationPrincipal CurrentUser actor, @PathVariable Long id) {
        CampaignDtos.CampaignResponse response = queries.toResponse(
                queries.requireOwnedByAdmin(id), actor);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{id}/approve")
    @Operation(summary = "Record the administrator approval")
    public ResponseEntity<CampaignDtos.CampaignResponse> approve(
            @AuthenticationPrincipal CurrentUser actor,
            @PathVariable Long id,
            @RequestBody(required = false) CampaignDtos.DecisionRequest request) {
        // The decision is implied by the route; an optional body may add a note.
        String note = request == null ? null : request.reason();
        return ResponseEntity.ok(queries.toResponse(
                workflow.decide(actor, id, DecisionParty.ADMIN,
                        new CampaignDtos.DecisionRequest(Decision.APPROVED, note)),
                actor));
    }

    @PostMapping("/{id}/reject")
    @Operation(summary = "Record the administrator rejection")
    public ResponseEntity<CampaignDtos.CampaignResponse> reject(
            @AuthenticationPrincipal CurrentUser actor,
            @PathVariable Long id,
            @RequestBody(required = false) CampaignDtos.DecisionRequest request) {
        // The decision is implied by the route, so only the reason is taken from the body and
        // only it may be supplied. A missing body yields a plain rejection.
        String reason = request == null ? null : request.reason();
        return ResponseEntity.ok(queries.toResponse(
                workflow.decide(actor, id, DecisionParty.ADMIN,
                        new CampaignDtos.DecisionRequest(Decision.REJECTED, reason)),
                actor));
    }

    @GetMapping("/{id}/history")
    @Operation(summary = "Status history for a campaign")
    public ResponseEntity<List<CampaignDtos.StatusHistoryResponse>> history(@PathVariable Long id) {
        return ResponseEntity.ok(queries.historyFor(id));
    }
}