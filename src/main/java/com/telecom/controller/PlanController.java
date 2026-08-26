package com.telecom.controller;

import com.telecom.model.dto.ApiResponse;
import com.telecom.model.dto.PlanDto;
import com.telecom.model.dto.RecommendationDto;
import com.telecom.service.PlanService;
import com.telecom.service.RecommendationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;

@RestController
@RequestMapping("/api/plans")
@RequiredArgsConstructor
@Tag(name = "Plans", description = "Telecom plan management and comparison APIs")
public class PlanController {

    private final PlanService planService;
    private final RecommendationService recommendationService;

    // ─── GET All Plans ─────────────────────────────────────────────

    @GetMapping
    @Operation(summary = "Get all active plans (paginated)")
    public ResponseEntity<ApiResponse<Page<PlanDto.Response>>> getAllPlans(
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(defaultValue = "monthlyPrice") String sortBy,
            @RequestParam(defaultValue = "asc") String sortDir) {

        Page<PlanDto.Response> plans = planService.getAllPlans(page, size, sortBy, sortDir);
        return ResponseEntity.ok(ApiResponse.success(plans));
    }

    // ─── GET Plan by ID ────────────────────────────────────────────

    @GetMapping("/{id}")
    @Operation(summary = "Get plan by ID")
    public ResponseEntity<ApiResponse<PlanDto.Response>> getPlanById(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(planService.getPlanById(id)));
    }

    // ─── Filter Plans ──────────────────────────────────────────────

    @PostMapping("/filter")
    @Operation(summary = "Filter plans by criteria")
    public ResponseEntity<ApiResponse<Page<PlanDto.Response>>> filterPlans(
            @RequestBody PlanDto.FilterRequest filter,
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "10") int size) {

        return ResponseEntity.ok(ApiResponse.success(planService.filterPlans(filter, page, size)));
    }

    // ─── Compare Plans ─────────────────────────────────────────────

    @GetMapping("/compare")
    @Operation(summary = "Compare 2–5 plans side by side")
    public ResponseEntity<ApiResponse<PlanDto.ComparisonResponse>> comparePlans(
            @RequestParam List<Long> ids) {

        return ResponseEntity.ok(ApiResponse.success(planService.comparePlans(ids)));
    }

    // ─── Get Providers ─────────────────────────────────────────────

    @GetMapping("/providers")
    @Operation(summary = "List all active providers")
    public ResponseEntity<ApiResponse<List<String>>> getProviders() {
        return ResponseEntity.ok(ApiResponse.success(planService.getAllProviders()));
    }

    // ─── "Best Plan for My Job" Recommender ─────────────────────────

    @GetMapping("/occupations")
    @Operation(summary = "List supported occupation profiles for the recommender")
    public ResponseEntity<ApiResponse<List<RecommendationDto.OccupationOption>>> getOccupations() {
        return ResponseEntity.ok(ApiResponse.success(recommendationService.getOccupationOptions()));
    }

    @GetMapping("/recommend")
    @Operation(summary = "Get the best plan recommendation for a given occupation")
    public ResponseEntity<ApiResponse<RecommendationDto.Response>> recommend(
            @RequestParam RecommendationDto.Occupation occupation,
            @RequestParam(required = false) BigDecimal maxBudget) {

        RecommendationDto.Request request = RecommendationDto.Request.builder()
                .occupation(occupation)
                .maxBudget(maxBudget)
                .build();

        return ResponseEntity.ok(ApiResponse.success(recommendationService.recommend(request)));
    }

    // ─── Create Plan (Admin) ───────────────────────────────────────

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Create a new plan [ADMIN]")
    public ResponseEntity<ApiResponse<PlanDto.Response>> createPlan(
            @Valid @RequestBody PlanDto.CreateRequest request) {

        PlanDto.Response created = planService.createPlan(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Plan created successfully", created));
    }

    // ─── Update Plan (Admin) ───────────────────────────────────────

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Update an existing plan [ADMIN]")
    public ResponseEntity<ApiResponse<PlanDto.Response>> updatePlan(
            @PathVariable Long id,
            @Valid @RequestBody PlanDto.UpdateRequest request) {

        return ResponseEntity.ok(ApiResponse.success("Plan updated successfully", planService.updatePlan(id, request)));
    }

    // ─── Delete Plan (Admin) ───────────────────────────────────────

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Soft-delete a plan [ADMIN]")
    public ResponseEntity<ApiResponse<Void>> deletePlan(@PathVariable Long id) {
        planService.deletePlan(id);
        return ResponseEntity.ok(ApiResponse.success("Plan discontinued successfully", null));
    }
}
