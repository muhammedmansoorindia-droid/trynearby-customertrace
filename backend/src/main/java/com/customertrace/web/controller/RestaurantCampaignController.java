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
 * Restaurant-side campaign review.
 *
 * <p>The deciding party is fixed to RESTAURANT by the route, and the service additionally
 * verifies that the signed-in restaurant owns the campaign, so one restaurant cannot approve or
 * reject another's campaign.
 */
@RestController
@RequestMapping("/api/restaurants/campaigns")
@Tag(name = "Restaurant campaigns")
public class RestaurantCampaignController {

    private final CampaignWorkflowService workflow;
    private final CampaignQueryService queries;

    public RestaurantCampaignController(CampaignWorkflowService workflow,
                                        CampaignQueryService queries) {
        this.workflow = workflow;
        this.queries = queries;
    }

    @GetMapping
    @Operation(summary = "Campaigns belonging to the signed-in restaurant")
    public ResponseEntity<List<CampaignDtos.CampaignResponse>> list(
            @AuthenticationPrincipal CurrentUser actor,
            @RequestParam(required = false) CampaignStatus status,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "NEWEST") String sort,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return ResponseEntity.ok(queries.listForRestaurant(actor, status, q,
                CampaignQueryService.pageOf(page, size, sort)));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Campaign detail, including both decision states")
    public ResponseEntity<CampaignDtos.CampaignResponse> get(
            @AuthenticationPrincipal CurrentUser actor, @PathVariable Long id) {
        return ResponseEntity.ok(
                queries.toResponse(queries.requireOwnedByRestaurant(actor, id), actor));
    }

    @PostMapping("/{id}/approve")
    @Operation(summary = "Record the restaurant approval")
    public ResponseEntity<CampaignDtos.CampaignResponse> approve(
            @AuthenticationPrincipal CurrentUser actor,
            @PathVariable Long id,
            @RequestBody(required = false) CampaignDtos.DecisionRequest request) {
        String note = request == null ? null : request.reason();
        return ResponseEntity.ok(queries.toResponse(
                workflow.decide(actor, id, DecisionParty.RESTAURANT,
                        new CampaignDtos.DecisionRequest(Decision.APPROVED, note)),
                actor));
    }

    @PostMapping("/{id}/reject")
    @Operation(summary = "Record the restaurant rejection")
    public ResponseEntity<CampaignDtos.CampaignResponse> reject(
            @AuthenticationPrincipal CurrentUser actor,
            @PathVariable Long id,
            @RequestBody(required = false) CampaignDtos.DecisionRequest request) {
        String reason = request == null ? null : request.reason();
        return ResponseEntity.ok(queries.toResponse(
                workflow.decide(actor, id, DecisionParty.RESTAURANT,
                        new CampaignDtos.DecisionRequest(Decision.REJECTED, reason)),
                actor));
    }

    @GetMapping("/{id}/history")
    @Operation(summary = "Status history for one of the restaurant's campaigns")
    public ResponseEntity<List<CampaignDtos.StatusHistoryResponse>> history(
            @AuthenticationPrincipal CurrentUser actor, @PathVariable Long id) {
        queries.requireOwnedByRestaurant(actor, id);
        return ResponseEntity.ok(queries.historyFor(id));
    }
}