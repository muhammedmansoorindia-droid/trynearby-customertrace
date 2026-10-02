package com.customertrace.web.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.customertrace.security.CurrentUser;
import com.customertrace.service.RedemptionService;
import com.customertrace.web.dto.RedemptionDtos;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

/**
 * Restaurant scan and redemption endpoints.
 *
 * <p>Every operation is scoped to the restaurant that owns the signed-in user, so one restaurant
 * cannot scan or redeem against another's campaign.
 */
@RestController
@RequestMapping("/api/restaurants/scan")
@Tag(name = "Scans and redemptions")
public class RedemptionController {

    private final RedemptionService redemptions;

    public RedemptionController(RedemptionService redemptions) {
        this.redemptions = redemptions;
    }

    /**
     * Validates a code without recording anything.
     *
     * <p>Backs the manual-entry lookup, so staff can confirm the offer before committing.
     */
    @GetMapping("/lookup")
    @Operation(summary = "Look up a promo code without recording a visit")
    public ResponseEntity<RedemptionDtos.LookupResponse> lookup(
            @AuthenticationPrincipal CurrentUser actor,
            @RequestParam String code) {
        return ResponseEntity.ok(redemptions.lookup(actor, code));
    }

    @PostMapping("/scan")
    @Operation(summary = "Record that a code was presented (interest only)")
    public ResponseEntity<RedemptionDtos.ScanResponse> scan(
            @AuthenticationPrincipal CurrentUser actor,
            @Valid @RequestBody RedemptionDtos.ScanRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(redemptions.recordScan(actor, request));
    }

    @PostMapping("/redeem")
    @Operation(summary = "Confirm a verified customer visit")
    public ResponseEntity<RedemptionDtos.RedemptionResponse> redeem(
            @AuthenticationPrincipal CurrentUser actor,
            @Valid @RequestBody RedemptionDtos.RedeemRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(redemptions.redeem(actor, request));
    }
}