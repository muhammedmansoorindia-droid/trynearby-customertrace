package com.customertrace.web.controller;

import java.time.LocalDate;
import java.util.List;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.customertrace.security.CurrentUser;
import com.customertrace.service.ReportService;
import com.customertrace.web.dto.ReportDtos;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Role-scoped reporting.
 *
 * <p>Each route sits under the namespace of its portal, so the Spring Security rules already
 * restrict who may call it; the service additionally resolves ownership from the authenticated
 * principal, so neither layer depends on the other for correctness.
 */
@RestController
@RequestMapping("/api/reports")
@Tag(name = "Reports")
public class ReportController {

    private final ReportService reports;

    public ReportController(ReportService reports) {
        this.reports = reports;
    }

    @GetMapping("/platform/summary")
    @Operation(summary = "Platform-wide metrics (administrator)")
    public ResponseEntity<ReportDtos.PlatformSummaryResponse> platformSummary(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate to) {
        return ResponseEntity.ok(reports.platformSummary(from, to));
    }

    @GetMapping("/platform/campaigns")
    @Operation(summary = "Per-campaign performance across the platform (administrator)")
    public ResponseEntity<List<ReportDtos.CampaignPerformanceResponse>> platformCampaigns(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate to) {
        return ResponseEntity.ok(reports.platformCampaigns(from, to));
    }

    @GetMapping("/platform/trend")
    @Operation(summary = "Daily platform scans and verified customers (administrator)")
    public ResponseEntity<ReportDtos.TrendResponse> platformTrend(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate to) {
        return ResponseEntity.ok(reports.platformTrend(from, to));
    }

    // ── Restaurant ───────────────────────────────────────────────────────────

    @GetMapping("/restaurant/summary")
    @Operation(summary = "Headline metrics for the signed-in restaurant")
    public ResponseEntity<ReportDtos.SummaryResponse> restaurantSummary(
            @AuthenticationPrincipal CurrentUser actor,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate to) {
        return ResponseEntity.ok(reports.restaurantSummary(actor, from, to));
    }

    @GetMapping("/restaurant/campaigns")
    @Operation(summary = "Per-campaign performance for the signed-in restaurant")
    public ResponseEntity<List<ReportDtos.CampaignPerformanceResponse>> restaurantCampaigns(
            @AuthenticationPrincipal CurrentUser actor,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate to) {
        return ResponseEntity.ok(reports.restaurantCampaigns(actor, from, to));
    }

    @GetMapping("/restaurant/trend")
    @Operation(summary = "Daily scans and verified customers for the signed-in restaurant")
    public ResponseEntity<ReportDtos.TrendResponse> restaurantTrend(
            @AuthenticationPrincipal CurrentUser actor,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate to) {
        return ResponseEntity.ok(reports.restaurantTrend(actor, from, to));
    }

    @GetMapping("/restaurant/status-breakdown")
    @Operation(summary = "Campaign counts by status for the signed-in restaurant")
    public ResponseEntity<ReportDtos.StatusBreakupResponse> restaurantStatus(
            @AuthenticationPrincipal CurrentUser actor) {
        return ResponseEntity.ok(reports.restaurantStatusBreakup(actor));
    }

    // ── Creator ──────────────────────────────────────────────────────────────

    @GetMapping("/creator/summary")
    @Operation(summary = "Headline metrics for the signed-in creator")
    public ResponseEntity<ReportDtos.SummaryResponse> creatorSummary(
            @AuthenticationPrincipal CurrentUser actor,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate to) {
        return ResponseEntity.ok(reports.creatorSummary(actor, from, to));
    }

    @GetMapping("/creator/campaigns")
    @Operation(summary = "Per-campaign performance for the signed-in creator")
    public ResponseEntity<List<ReportDtos.CampaignPerformanceResponse>> creatorCampaigns(
            @AuthenticationPrincipal CurrentUser actor,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate to) {
        return ResponseEntity.ok(reports.creatorCampaigns(actor, from, to));
    }

    @GetMapping("/creator/trend")
    @Operation(summary = "Daily scans and verified customers for the signed-in creator")
    public ResponseEntity<ReportDtos.TrendResponse> creatorTrend(
            @AuthenticationPrincipal CurrentUser actor,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate to) {
        return ResponseEntity.ok(reports.creatorTrend(actor, from, to));
    }

    @GetMapping("/creator/status-breakdown")
    @Operation(summary = "Campaign counts by status for the signed-in creator")
    public ResponseEntity<ReportDtos.StatusBreakupResponse> creatorStatus(
            @AuthenticationPrincipal CurrentUser actor) {
        return ResponseEntity.ok(reports.creatorStatusBreakup(actor));
    }
}