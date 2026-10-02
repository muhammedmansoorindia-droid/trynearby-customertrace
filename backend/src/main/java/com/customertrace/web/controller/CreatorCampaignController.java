package com.customertrace.web.controller;

import java.util.List;

import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.customertrace.domain.Campaign;
import com.customertrace.domain.CampaignStatus;
import com.customertrace.domain.Creator;
import com.customertrace.domain.Decision;
import com.customertrace.domain.DecisionParty;
import com.customertrace.domain.PromoCode;
import com.customertrace.domain.Restaurant;
import com.customertrace.security.CurrentUser;
import com.customertrace.service.CampaignQueryService;
import com.customertrace.service.CampaignWorkflowService;
import com.customertrace.web.dto.CampaignDtos;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

/**
 * Campaign endpoints for the creator portal.
 *
 * <p>Ownership is enforced in {@link CampaignWorkflowService}; a creator can only create,
 * submit and read their own campaigns. Admin and restaurant decisions live under
 * {@code /api/admin/campaigns} and {@code /api/restaurants/campaigns} respectively, so the
 * deciding party is fixed by the route as well as by the stored role.
 */
@RestController
@RequestMapping("/api/creator/campaigns")
@Tag(name = "Creator campaigns")
public class CreatorCampaignController {

    private final CampaignWorkflowService workflow;
    private final CampaignQueryService queries;

    public CreatorCampaignController(CampaignWorkflowService workflow, CampaignQueryService queries) {
        this.workflow = workflow;
        this.queries = queries;
    }

    @PostMapping
    @Operation(summary = "Create a draft campaign owned by the signed-in creator")
    public ResponseEntity<CampaignDtos.CampaignResponse> create(
            @AuthenticationPrincipal CurrentUser actor,
            @Valid @RequestBody CampaignDtos.CreateCampaignRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(queries.toResponse(workflow.create(actor, request), actor));
    }

    @PostMapping("/{id}/submit")
    @Operation(summary = "Submit a draft or rejected campaign for dual approval")
    public ResponseEntity<CampaignDtos.CampaignResponse> submit(
            @AuthenticationPrincipal CurrentUser actor,
            @PathVariable Long id) {
        return ResponseEntity.ok(queries.toResponse(workflow.submit(actor, id), actor));
    }

    @GetMapping
    @Operation(summary = "List the signed-in creator's campaigns")
    public ResponseEntity<List<CampaignDtos.CampaignResponse>> list(
            @AuthenticationPrincipal CurrentUser actor,
            @RequestParam(required = false) CampaignStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(queries.listForCreator(actor, status, PageRequest.of(page, size)));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Campaign detail, including both approval states")
    public ResponseEntity<CampaignDtos.CampaignResponse> get(
            @AuthenticationPrincipal CurrentUser actor, @PathVariable Long id) {
        return ResponseEntity.ok(queries.toResponse(queries.requireOwnedByCreator(actor, id), actor));
    }

    @GetMapping("/{id}/history")
    @Operation(summary = "Status history for one of the creator's campaigns")
    public ResponseEntity<List<CampaignDtos.StatusHistoryResponse>> history(
            @AuthenticationPrincipal CurrentUser actor, @PathVariable Long id) {
        // Reading the history also enforces ownership.
        queries.requireOwnedByCreator(actor, id);
        return ResponseEntity.ok(queries.historyFor(id));
    }
}