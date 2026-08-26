package com.telecom.service;

import com.telecom.model.dto.PlanDto;
import com.telecom.model.dto.RecommendationDto;
import com.telecom.model.dto.RecommendationDto.Occupation;
import com.telecom.model.entity.Plan;
import com.telecom.repository.PlanRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.stream.Collectors;

/**
 * "Best Plan for My Job" Recommender.
 * <p>
 * Every occupation has a data/calling/feature profile. We filter active
 * plans against that profile's minimum requirements, rank the survivors by
 * how well they match (data headroom, hotspot, unlimited calls, relevant
 * add-ons, rating) and cheapest price, then explain the pick in plain
 * English — the same way a knowledgeable friend would.
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class RecommendationService {

    private final PlanRepository planRepository;
    private final PlanService planService;

    private static final int DAYS_IN_MONTH = 30;

    // ─── Occupation Profiles ──────────────────────────────────────

    private record OccupationProfile(
            Occupation occupation,
            String label,
            String icon,
            double minDataPerDayGB,      // 0 = no minimum
            boolean requiresUnlimitedData,
            boolean needsHotspot,
            boolean needsUnlimitedCalls,
            List<String> preferredKeywords,
            String useCaseNote,          // e.g. "for video calls and cloud syncing"
            String requirementSummary
    ) {}

    private static final Map<Occupation, OccupationProfile> PROFILES = new EnumMap<>(Map.of(
            Occupation.STUDENT, new OccupationProfile(
                    Occupation.STUDENT, "Student", "🎓",
                    1.0, false, false, false,
                    List.of("student", "spotify", "netflix", "education", "streaming", "rollover"),
                    "for online classes, study apps and streaming",
                    "Minimum 1GB/day, budget-friendly, student perks a bonus"
            ),
            Occupation.WFH_PROFESSIONAL, new OccupationProfile(
                    Occupation.WFH_PROFESSIONAL, "WFH Professional", "💻",
                    2.0, false, true, false,
                    List.of("meet", "zoom", "video", "hotspot", "hd streaming", "priority", "cloud"),
                    "for video calls, cloud syncing and a reliable hotspot backup",
                    "Minimum 2GB/day, mobile hotspot for backup connectivity, video-call ready"
            ),
            Occupation.FIELD_SALES, new OccupationProfile(
                    Occupation.FIELD_SALES, "Field Sales", "🚗",
                    1.5, false, true, true,
                    List.of("hotspot", "navigation", "roaming", "priority", "unlimited calling", "5g"),
                    "for maps, calls on the move and hotspot tethering",
                    "Minimum 1.5GB/day, unlimited/near-unlimited calling, hotspot for on-the-go work"
            ),
            Occupation.DAILY_WAGE_WORKER, new OccupationProfile(
                    Occupation.DAILY_WAGE_WORKER, "Daily Wage Worker", "🛠️",
                    0.5, false, false, false,
                    List.of("no contract", "prepaid", "wi-fi calling", "carry-over"),
                    "while keeping your monthly cost as low as possible",
                    "Minimum 0.5GB/day, lowest possible monthly cost, no long contracts"
            )
    ));

    // ─── Public API ────────────────────────────────────────────────

    public List<RecommendationDto.OccupationOption> getOccupationOptions() {
        return PROFILES.values().stream()
                .map(p -> RecommendationDto.OccupationOption.builder()
                        .value(p.occupation())
                        .label(p.label())
                        .icon(p.icon())
                        .description(p.requirementSummary())
                        .build())
                .collect(Collectors.toList());
    }

    public RecommendationDto.Response recommend(RecommendationDto.Request request) {
        if (request == null || request.getOccupation() == null) {
            throw new IllegalArgumentException("Occupation is required to generate a recommendation");
        }

        OccupationProfile profile = PROFILES.get(request.getOccupation());
        if (profile == null) {
            throw new IllegalArgumentException("Unsupported occupation: " + request.getOccupation());
        }

        List<Plan> activePlans = planRepository.findByStatus(Plan.PlanStatus.ACTIVE, Pageable.unpaged()).getContent();
        if (activePlans.isEmpty()) {
            throw new IllegalStateException("No active plans available to recommend from");
        }

        double minMonthlyGB = profile.minDataPerDayGB() * DAYS_IN_MONTH;

        List<Plan> candidates = filterCandidates(activePlans, profile, minMonthlyGB, request.getMaxBudget());

        // Progressive relaxation so we always come back with *something* useful,
        // just like a human advisor would rather than saying "no match".
        boolean relaxedHotspot = false;
        boolean relaxedBudget = false;
        boolean relaxedData = false;

        if (candidates.isEmpty() && profile.needsHotspot()) {
            candidates = filterCandidates(activePlans, withoutHotspot(profile), minMonthlyGB, request.getMaxBudget());
            relaxedHotspot = true;
        }
        if (candidates.isEmpty() && request.getMaxBudget() != null) {
            candidates = filterCandidates(activePlans, profile, minMonthlyGB, null);
            relaxedBudget = true;
        }
        if (candidates.isEmpty()) {
            candidates = filterCandidates(activePlans, profile, 0, null);
            relaxedData = true;
        }
        if (candidates.isEmpty()) {
            candidates = activePlans; // absolute last resort
        }

        List<PlanDto.Response> ranked = candidates.stream()
                .map(planService::toResponse)
                .sorted(rankingComparator(profile))
                .collect(Collectors.toList());

        PlanDto.Response best = ranked.get(0);
        List<PlanDto.Response> alternatives = ranked.stream()
                .skip(1)
                .limit(2)
                .collect(Collectors.toList());

        List<String> highlights = buildHighlights(best, profile, minMonthlyGB, relaxedHotspot, relaxedData);
        String reason = buildReason(best, profile, minMonthlyGB, relaxedHotspot, relaxedBudget, relaxedData);

        return RecommendationDto.Response.builder()
                .occupation(profile.occupation())
                .occupationLabel(profile.label())
                .requirementSummary(profile.requirementSummary())
                .minDataPerDayGB(profile.minDataPerDayGB())
                .recommendedPlan(best)
                .reason(reason)
                .matchHighlights(highlights)
                .alternatives(alternatives)
                .build();
    }

    // ─── Filtering ─────────────────────────────────────────────────

    private OccupationProfile withoutHotspot(OccupationProfile p) {
        return new OccupationProfile(p.occupation(), p.label(), p.icon(), p.minDataPerDayGB(),
                p.requiresUnlimitedData(), false, p.needsUnlimitedCalls(), p.preferredKeywords(),
                p.useCaseNote(), p.requirementSummary());
    }

    private List<Plan> filterCandidates(List<Plan> plans, OccupationProfile profile, double minMonthlyGB, BigDecimal maxBudget) {
        return plans.stream()
                .filter(p -> p.getDataLimitGB() == null || p.getDataLimitGB() >= minMonthlyGB)
                .filter(p -> !profile.needsHotspot() || Boolean.TRUE.equals(p.getHotspotEnabled()))
                .filter(p -> maxBudget == null || p.getMonthlyPrice().compareTo(maxBudget) <= 0)
                .collect(Collectors.toList());
    }

    // ─── Ranking ───────────────────────────────────────────────────

    private Comparator<PlanDto.Response> rankingComparator(OccupationProfile profile) {
        return Comparator
                .comparingInt((PlanDto.Response p) -> -matchScore(p, profile)) // higher score first
                .thenComparing(PlanDto.Response::getMonthlyPrice)              // then cheapest
                .thenComparing(Comparator.comparing(PlanDto.Response::getAverageRating,
                        Comparator.nullsLast(Comparator.reverseOrder())));
    }

    private int matchScore(PlanDto.Response plan, OccupationProfile profile) {
        int score = 0;
        if (Boolean.TRUE.equals(plan.getHotspotEnabled()) && profile.needsHotspot()) score += 3;
        if (profile.needsUnlimitedCalls() && plan.getCallMinutes() == null) score += 3;
        if (Boolean.TRUE.equals(plan.getFiveGEnabled())) score += 1;
        score += keywordMatchCount(plan, profile) * 2;
        if (plan.getAverageRating() != null) score += (int) Math.round(plan.getAverageRating());
        return score;
    }

    private int keywordMatchCount(PlanDto.Response plan, OccupationProfile profile) {
        String haystack = ((plan.getDescription() == null ? "" : plan.getDescription()) + " " +
                String.join(" ", Optional.ofNullable(plan.getAdditionalFeatures()).orElse(List.of())))
                .toLowerCase();
        int count = 0;
        for (String kw : profile.preferredKeywords()) {
            if (haystack.contains(kw.toLowerCase())) count++;
        }
        return count;
    }

    // ─── Explanation Builders ──────────────────────────────────────

    private List<String> buildHighlights(PlanDto.Response plan, OccupationProfile profile, double minMonthlyGB,
                                          boolean relaxedHotspot, boolean relaxedData) {
        List<String> highlights = new ArrayList<>();

        if (!relaxedData) {
            highlights.add(plan.getDataLimitGB() == null
                    ? "Unlimited data comfortably covers your " + trimZeros(profile.minDataPerDayGB()) + "GB/day need"
                    : "Meets your " + trimZeros(profile.minDataPerDayGB()) + "GB/day requirement (~"
                      + plan.getDataLimitGB() + "GB/month)");
        }

        if (profile.needsHotspot()) {
            highlights.add(Boolean.TRUE.equals(plan.getHotspotEnabled())
                    ? "Includes mobile hotspot for backup connectivity"
                    : "⚠ No hotspot on this plan — closest match on data & price");
        }

        if (profile.needsUnlimitedCalls()) {
            highlights.add(plan.getCallMinutes() == null
                    ? "Unlimited calling included"
                    : plan.getCallMinutes() + " call minutes/month");
        }

        if (Boolean.TRUE.equals(plan.getFiveGEnabled())) {
            highlights.add("5G enabled");
        }

        String matchedFeature = matchedFeature(plan, profile);
        if (matchedFeature != null) {
            highlights.add("Bonus perk for you: " + matchedFeature);
        }

        if (relaxedHotspot) {
            highlights.add("⚠ Relaxed the hotspot requirement — no active plan with hotspot fit your other needs");
        }

        return highlights;
    }

    private String buildReason(PlanDto.Response plan, OccupationProfile profile, double minMonthlyGB,
                                boolean relaxedHotspot, boolean relaxedBudget, boolean relaxedData) {
        StringBuilder sb = new StringBuilder();

        sb.append("For a ").append(profile.label()).append(" you need minimum ")
                .append(trimZeros(profile.minDataPerDayGB())).append("GB/day (~")
                .append((int) Math.round(minMonthlyGB)).append("GB/month) ")
                .append(profile.useCaseNote()).append(". ");

        sb.append(plan.getName()).append(" ").append(plan.getProvider());
        sb.append(" gives ").append(perDayDisplay(plan.getDataLimitGB()));

        String matchedFeature = matchedFeature(plan, profile);
        if (matchedFeature != null) {
            sb.append(" with ").append(matchedFeature).append(" included");
        } else if (profile.needsHotspot() && Boolean.TRUE.equals(plan.getHotspotEnabled())) {
            sb.append(" with mobile hotspot included");
        }

        sb.append(" at ₹").append(plan.getMonthlyPrice()).append("/month");
        sb.append(" — best fit for your needs.");

        if (relaxedData) {
            sb.append(" (Note: no active plan fully met the ")
                    .append(trimZeros(profile.minDataPerDayGB())).append("GB/day target, so this is the closest match.)");
        } else if (relaxedHotspot) {
            sb.append(" (Note: hotspot requirement was relaxed to find the best available match.)");
        } else if (relaxedBudget) {
            sb.append(" (Note: this exceeds your budget slightly — it was the best fit meeting your data/feature needs.)");
        }

        return sb.toString();
    }

    private String matchedFeature(PlanDto.Response plan, OccupationProfile profile) {
        List<String> features = Optional.ofNullable(plan.getAdditionalFeatures()).orElse(List.of());
        for (String feature : features) {
            String lower = feature.toLowerCase();
            for (String kw : profile.preferredKeywords()) {
                if (lower.contains(kw.toLowerCase())) return feature;
            }
        }
        return null;
    }

    private String perDayDisplay(Integer monthlyGB) {
        if (monthlyGB == null) return "unlimited data";
        BigDecimal perDay = BigDecimal.valueOf(monthlyGB)
                .divide(BigDecimal.valueOf(DAYS_IN_MONTH), 1, RoundingMode.HALF_UP);
        return trimZeros(perDay.doubleValue()) + "GB/day (" + monthlyGB + "GB/month)";
    }

    private String trimZeros(double value) {
        BigDecimal bd = BigDecimal.valueOf(value).stripTrailingZeros();
        return bd.scale() < 0 ? bd.toBigInteger().toString() : bd.toPlainString();
    }
}
