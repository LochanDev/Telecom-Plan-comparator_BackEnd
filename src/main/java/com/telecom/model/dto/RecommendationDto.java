package com.telecom.model.dto;

import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.math.BigDecimal;
import java.util.List;

// ─── "Best Plan for My Job" Recommender DTOs ─────────────────────

public class RecommendationDto {

    /**
     * Supported occupation profiles. Each profile carries its own
     * minimum data/day, calling & feature needs used by the
     * recommendation engine.
     */
    public enum Occupation {
        STUDENT,
        WFH_PROFESSIONAL,
        FIELD_SALES,
        DAILY_WAGE_WORKER
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class Request {
        @NotNull
        private Occupation occupation;
        /** Optional soft budget cap in the plan's monthly price currency. */
        private BigDecimal maxBudget;
    }

    /** Lightweight descriptor used to populate the occupation picker on the UI. */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class OccupationOption {
        private Occupation value;
        private String label;
        private String icon;
        private String description;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class Response {
        private Occupation occupation;
        private String occupationLabel;
        private String requirementSummary;      // e.g. "Minimum 2GB/day, video-call ready, hotspot backup"
        private Double minDataPerDayGB;          // null = unlimited requirement
        private PlanDto.Response recommendedPlan;
        private String reason;                   // AI-style recommendation sentence
        private List<String> matchHighlights;    // short bullet points explaining the fit
        private List<PlanDto.Response> alternatives; // next-best options
    }
}
