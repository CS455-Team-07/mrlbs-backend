package com.mrlbs.ai.review;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mrlbs.ai.GeminiClient;
import com.mrlbs.telemetry.analyzer.RequesterHistoryProfile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class AiReviewAgentService {

    private static final Logger log = LoggerFactory.getLogger(AiReviewAgentService.class);

    private static final String SYSTEM_INSTRUCTION = """
            You are the MRLBS AI Request Review Agent at IIT Kanpur.
            You provide an advisory recommendation to the human Admin reviewing a pending lab booking.

            CRITICAL RULES:
            1. Permitted recommendations: RECOMMEND_APPROVAL, RECOMMEND_REJECTION, NEEDS_HUMAN_REVIEW.
            2. You are purely advisory; the human Admin makes the final decision.
            3. Target-Resource Health vs. Requester History:
               - The booking under review is for a future time slot; future telemetry cannot exist yet.
               - NEVER attribute present target-resource health (e.g., degraded status, high GPU/memory load, high temperature) to the requester without explicit evidence. Present device metrics reflect an isolated point-in-time snapshot from other previous or ongoing workloads, completely independent of this requester.
               - NEVER treat current resource health as historical evidence or as a guarantee of future availability.
            4. Explaining Resource Degraded State:
               - If the target resource is currently in a degraded or heavily loaded state, explain how this present condition could impact immediate workload performance if the condition persists when the requested window begins.
               - Do NOT assert that the resource will remain degraded in the future, and NEVER invent unstated scheduling, maintenance, or downtime policies.
            5. Booking History Breakdown, Cancellations & Pattern Analysis:
               - Distinguish completed, cancelled, rejected, and pending bookings accurately.
               - Normal cancellations or rejected requests are standard scheduling changes (e.g., student course adjustments), NOT policy violations or evidence of misconduct.
               - Pattern Analysis across Reservations:
                 Carefully analyze the telemetry breakdown of each individual past booking to detect requester behavioral patterns (e.g., whether the student consistently utilizes the GPU productively across multiple sessions, has a habit of high-burst compute, or showed idle utilization in previous slots). Detail these observed session-by-session patterns in supportingEvidence.

            6. Insufficient Historical Evidence:
               - When the requester has no prior completed bookings or reliable historical telemetry on the target resource, explicitly explain that the requester's historical usage cannot be evaluated.
               - In the absence of sufficient completed historical evidence, prefer NEEDS_HUMAN_REVIEW.
            7. Telemetry Caveats & Demonstration Data:
               - Telemetry represents device-level measurements during exclusive reservation windows. Never claim individual user or job efficiency when the system only records hardware device metrics.
               - Treat demonstration telemetry as an explicit data limitation and disclose it in dataLimitations.
               - NEVER recommend REJECTION solely due to missing, incomplete, unavailable, or demonstration-only telemetry.
            8. Tone, Structure & Separation:
               - Make rationale concise (1-2 sentences summarizing the core advisory takeaway for the human Admin). Do NOT duplicate detailed metrics in rationale.
               - Place raw numbers, observed booking patterns across past sessions, and health metrics strictly in supportingEvidence.
               - Keep data limitations, caveats, demonstration flags, and attribution boundaries strictly in dataLimitations.
            9. Acknowledge uncertainty and avoid unsupported predictions. Ground all reasoning strictly in the factual evidence provided. Never hallucinate metrics.

            OUTPUT FORMAT:
            You must return a valid JSON object matching this schema:
            {
              "recommendation": "RECOMMEND_APPROVAL | RECOMMEND_REJECTION | NEEDS_HUMAN_REVIEW",
              "confidenceScore": 0.85,
              "rationale": "Concise 1-2 sentence advisory takeaway for the Admin based strictly on observed evidence",
              "supportingEvidence": "Factual breakdown of observed patterns across past sessions, booking counts, time-series, and health snapshot",
              "dataLimitations": "Explicit disclosure of data limits, demonstration flags, uncertainty, and attribution boundaries",
              "isDemonstrationData": true
            }

            NOTE ON CONFIDENCE SCORE:
            confidenceScore (0.1 to 1.0) represents YOUR confidence in your advisory recommendation based on the completeness and reliability of the available evidence. It is NOT a user risk score, misconduct probability, or behavioral prediction.
            """;

    private final GeminiClient geminiClient;
    private final ObjectMapper objectMapper;

    public AiReviewAgentService(GeminiClient geminiClient, ObjectMapper objectMapper) {
        this.geminiClient = geminiClient;
        this.objectMapper = objectMapper;
    }

    public ReviewEvaluationResult evaluateBooking(BookingReviewEvent event, RequesterHistoryProfile profile) {
        String userPrompt = buildEvaluationPrompt(event, profile);

        if (geminiClient.isConfigured()) {
            try {
                Optional<String> jsonResponseOpt = geminiClient.generateStructuredContent(SYSTEM_INSTRUCTION, userPrompt);
                if (jsonResponseOpt.isPresent()) {
                    ReviewEvaluationResult result = parseGeminiResponse(jsonResponseOpt.get(), profile);
                    if (result != null) {
                        return result;
                    }
                }
            } catch (Exception e) {
                log.warn("Gemini evaluation error for booking {}: {}", event.bookingId(), e.getMessage());
            }
        }

        // Rule-based deterministic fallback when Gemini is offline, unconfigured, or returns unparseable content
        return evaluateRuleBasedFallback(profile);
    }

    public String buildEvaluationPrompt(BookingReviewEvent event, RequesterHistoryProfile profile) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== PENDING BOOKING REQUEST ===\n");
        sb.append(String.format("- Booking ID: %s\n", event.bookingId()));
        sb.append(String.format("- Target Resource: %s (%s)\n", event.resourceName(), event.resourceType()));
        sb.append(String.format("- Requested Window: %s to %s\n", event.startTime(), event.endTime()));
        sb.append(String.format("- Requester Member ID: %s\n\n", profile.memberId()));

        sb.append("=== CURRENT RESOURCE HEALTH (Present Snapshot Only) ===\n");
        sb.append("- NOTE: Isolated point-in-time snapshot of the target resource right now. Never treat as historical evidence or guarantee of future availability. Reflects external/previous workloads and must NEVER be attributed to this requester.\n");
        if (profile.currentHealth() != null) {
            sb.append(String.format("- Status: %s | GPU Util: %.1f%% | Mem Util: %.1f%% | Temp: %.1f°C | Power: %.1fW\n",
                    profile.currentHealth().healthStatus(),
                    profile.currentHealth().gpuUtilizationPct(),
                    profile.currentHealth().gpuMemoryUtilizationPct(),
                    profile.currentHealth().temperatureCelsius(),
                    profile.currentHealth().powerDrawWatts()));
            sb.append(String.format("- Source: %s | Demo Data: %b\n\n",
                    profile.currentHealth().dataSource(),
                    profile.currentHealth().isDemonstrationData()));
        } else {
            sb.append("- Current health status: UNAVAILABLE\n\n");
        }

        sb.append("=== REQUESTER TRACK RECORD ===\n");
        sb.append(String.format("- Prior Bookings Total: %d (Completed: %d, Cancelled: %d, Rejected: %d, Pending Approval: %d)\n",
                profile.priorBookingsTotal(), profile.priorBookingsCompleted(), profile.priorBookingsCancelled(),
                profile.priorBookingsRejected(), profile.priorBookingsPending()));
        sb.append("- NOTE: Standard cancellations or rejected requests are normal scheduling changes, NOT evidence of misconduct or policy violations.\n");
        if (profile.priorBookingsCompleted() == 0) {
            sb.append("- NOTE: Requester has zero completed bookings on this resource; historical usage cannot be evaluated. Prefer NEEDS_HUMAN_REVIEW.\n");
        }
        sb.append(String.format("- Overall Target GPU Avg Util: %s | Peak Util: %s | Overall Quality: %s\n",
                profile.historicalAvgGpuUtilPct() != null ? profile.historicalAvgGpuUtilPct() + "%" : "N/A",
                profile.historicalPeakGpuUtilPct() != null ? profile.historicalPeakGpuUtilPct() + "%" : "N/A",
                profile.historicalTelemetryQuality()));
        sb.append(String.format("- System Attribution Limitation: %s\n\n", profile.dataLimitations()));

        sb.append("=== DETAILED HISTORICAL EVIDENCE BY PAST COMPLETED BOOKING ===\n");
        if (profile.pastBookingTelemetryRecords() == null || profile.pastBookingTelemetryRecords().isEmpty()) {
            sb.append("- No eligible past completed bookings with telemetry recorded for this resource.\n");
        } else {
            int bookingIdx = 1;
            for (var record : profile.pastBookingTelemetryRecords()) {
                sb.append(String.format("Past Booking #%d [ID: %s]:\n", bookingIdx++, record.bookingId()));
                sb.append(String.format("  - Time Window: %s to %s\n", record.startTime(), record.endTime()));
                sb.append(String.format("  - Telemetry Quality: %s | Coverage: %.1f%% | Missing Data: %b%s\n",
                        record.dataQuality(), record.coveragePct(), record.hasMissingData(),
                        record.missingDataReason() != null ? " (" + record.missingDataReason() + ")" : ""));
                sb.append(String.format("  - Booking Summary: Avg GPU: %s | Peak GPU: %s | Avg Mem: %s | Avg Temp: %s | Avg Power: %s\n",
                        record.avgGpuUtilPct() != null ? record.avgGpuUtilPct() + "%" : "N/A",
                        record.peakGpuUtilPct() != null ? record.peakGpuUtilPct() + "%" : "N/A",
                        record.avgMemoryUtilPct() != null ? record.avgMemoryUtilPct() + "%" : "N/A",
                        record.avgTempCelsius() != null ? record.avgTempCelsius() + "°C" : "N/A",
                        record.avgPowerWatts() != null ? record.avgPowerWatts() + "W" : "N/A"));

                if (record.measurements() != null && !record.measurements().isEmpty()) {
                    sb.append(String.format("  - Representative Time-Series Telemetry (%d of %d measurements):\n",
                            record.measurements().size(), record.totalMeasurementsCount()));
                    for (var m : record.measurements()) {
                        sb.append(String.format("    * [%s] GPU: %.1f%% | Mem: %.1f%% | Temp: %.1f°C | Power: %.1fW\n",
                                m.timestamp(), m.gpuUtilizationPct(), m.gpuMemoryUtilizationPct(),
                                m.temperatureCelsius(), m.powerDrawWatts()));
                    }
                } else {
                    sb.append("  - Telemetry measurements: None recorded for this window.\n");
                }
                sb.append("\n");
            }
        }
        return sb.toString();
    }

    private ReviewEvaluationResult parseGeminiResponse(String jsonText, RequesterHistoryProfile profile) {
        try {
            JsonNode root = objectMapper.readTree(jsonText);
            String recStr = root.path("recommendation").asText("NEEDS_HUMAN_REVIEW").toUpperCase();
            AiReviewRecommendation recommendation;
            try {
                recommendation = AiReviewRecommendation.valueOf(recStr);
            } catch (IllegalArgumentException e) {
                recommendation = AiReviewRecommendation.NEEDS_HUMAN_REVIEW;
            }

            double confidence = root.path("confidenceScore").asDouble(0.85);
            String rationale = root.path("rationale").asText("AI evaluation completed.");
            String evidence = root.path("supportingEvidence").asText("");
            String limitations = root.path("dataLimitations").asText(profile.dataLimitations());
            boolean isDemo = root.path("isDemonstrationData").asBoolean(true);

            // Safety Guard: NEVER allow REJECTION solely because telemetry is missing/unavailable/demonstration-only
            if (recommendation == AiReviewRecommendation.RECOMMEND_REJECTION
                    && ("UNAVAILABLE".equalsIgnoreCase(profile.historicalTelemetryQuality())
                        || "NO_PRIOR_USAGE".equalsIgnoreCase(profile.historicalTelemetryQuality())
                        || "PARTIAL".equalsIgnoreCase(profile.historicalTelemetryQuality()))) {
                log.info("Overriding Gemini REJECTION to NEEDS_HUMAN_REVIEW: missing, partial, or demo telemetry cannot justify rejection.");
                recommendation = AiReviewRecommendation.NEEDS_HUMAN_REVIEW;
                rationale = "Overridden to NEEDS_HUMAN_REVIEW: Absence or limitation of historical telemetry cannot justify rejection. " + rationale;
            }

            return new ReviewEvaluationResult(
                    recommendation,
                    Math.min(1.0, Math.max(0.1, confidence)),
                    rationale,
                    evidence,
                    limitations,
                    isDemo,
                    geminiClient.getModelName()
            );
        } catch (Exception e) {
            log.warn("Failed to parse Gemini JSON output: {}. Raw: {}", e.getMessage(), jsonText);
            return null;
        }
    }

    public ReviewEvaluationResult evaluateRuleBasedFallback(RequesterHistoryProfile profile) {
        // Safe heuristic fallback when AI model is offline or unconfigured
        // 1. When no completed booking history exists, historical usage cannot be evaluated -> NEEDS_HUMAN_REVIEW
        if (profile.priorBookingsCompleted() == 0) {
            return new ReviewEvaluationResult(
                    AiReviewRecommendation.NEEDS_HUMAN_REVIEW,
                    0.75,
                    "Requester has no prior completed reservations on this resource to evaluate historical usage patterns. Admin review advised.",
                    String.format("Prior bookings total: %d (Completed: 0, Cancelled: %d, Rejected: %d, Pending: %d)",
                            profile.priorBookingsTotal(), profile.priorBookingsCancelled(),
                            profile.priorBookingsRejected(), profile.priorBookingsPending()),
                    profile.dataLimitations(),
                    true,
                    "RULE_BASED_FALLBACK"
            );
        }

        // 2. Strong historical hardware utilization on completed bookings -> RECOMMEND_APPROVAL
        if (profile.hasHistoricalTelemetry() && profile.historicalAvgGpuUtilPct() != null && profile.historicalAvgGpuUtilPct() >= 50.0) {
            return new ReviewEvaluationResult(
                    AiReviewRecommendation.RECOMMEND_APPROVAL,
                    0.90,
                    String.format("Member has strong past hardware utilization (avg %.1f%%) across %d completed reservations.",
                            profile.historicalAvgGpuUtilPct(), profile.priorBookingsCompleted()),
                    String.format("Completed: %d, Avg Util: %.1f%%, Peak: %.1f%%",
                            profile.priorBookingsCompleted(), profile.historicalAvgGpuUtilPct(),
                            profile.historicalPeakGpuUtilPct() != null ? profile.historicalPeakGpuUtilPct() : 0.0),
                    profile.dataLimitations(),
                    true,
                    "RULE_BASED_FALLBACK"
            );
        }

        // 3. Default safe recommendation for completed booking history
        return new ReviewEvaluationResult(
                AiReviewRecommendation.RECOMMEND_APPROVAL,
                0.80,
                "Requester has completed reservations on record. Target resource is operational.",
                String.format("Completed bookings: %d, Cancelled: %d",
                        profile.priorBookingsCompleted(), profile.priorBookingsCancelled()),
                profile.dataLimitations(),
                true,
                "RULE_BASED_FALLBACK"
        );
    }

    public record ReviewEvaluationResult(
            AiReviewRecommendation recommendation,
            Double confidenceScore,
            String rationale,
            String supportingEvidence,
            String dataLimitations,
            boolean isDemonstrationData,
            String modelName
    ) {}
}
