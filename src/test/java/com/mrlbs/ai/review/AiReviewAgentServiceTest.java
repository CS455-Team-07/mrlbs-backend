package com.mrlbs.ai.review;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mrlbs.ai.GeminiClient;
import com.mrlbs.telemetry.analyzer.PastBookingTelemetryRecord;
import com.mrlbs.telemetry.analyzer.RequesterHistoryProfile;
import com.mrlbs.telemetry.client.ExternalResourceHealth;
import com.mrlbs.telemetry.client.ExternalTelemetryMeasurement;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AiReviewAgentServiceTest {

    @Mock
    private GeminiClient geminiClient;

    private ObjectMapper objectMapper;
    private AiReviewAgentService agentService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        objectMapper = new ObjectMapper();
        agentService = new AiReviewAgentService(geminiClient, objectMapper);
    }

    private BookingReviewEvent createEvent(UUID bookingId) {
        return new BookingReviewEvent(
                bookingId,
                UUID.randomUUID(),
                UUID.randomUUID(),
                "GPU Node 1",
                "GPU",
                LocalDateTime.of(2026, 10, 16, 10, 0),
                LocalDateTime.of(2026, 10, 16, 12, 0)
        );
    }

    private RequesterHistoryProfile createProfile(UUID memberId, int completed, int cancelled,
                                                  Double avgUtil, String quality) {
        return new RequesterHistoryProfile(
                memberId,
                UUID.randomUUID(),
                "gpu-node-01",
                completed + cancelled,
                completed,
                cancelled,
                avgUtil,
                avgUtil != null ? avgUtil + 10.0 : null,
                quality,
                new ExternalResourceHealth("gpu-node-01", "HEALTHY", 30.0, 50.0, 65.0, 220.0, Instant.now(), "DEMO", true),
                avgUtil != null,
                "Device-level reservation measurements only."
        );
    }

    @Test
    void testEvaluateBooking_GeminiRecommendApproval() {
        UUID bookingId = UUID.randomUUID();
        BookingReviewEvent event = createEvent(bookingId);
        RequesterHistoryProfile profile = createProfile(event.memberId(), 4, 0, 82.0, "COMPLETE");

        String geminiJson = """
            {
                "recommendation": "RECOMMEND_APPROVAL",
                "confidenceScore": 0.92,
                "rationale": "Requester has 4 completed bookings with high hardware utilization (avg 82%). Current GPU is healthy.",
                "supportingEvidence": "Completed: 4, Avg Util: 82%, Cancellations: 0",
                "dataLimitations": "Device-level measurements only.",
                "isDemonstrationData": true
            }
            """;

        when(geminiClient.isConfigured()).thenReturn(true);
        when(geminiClient.getModelName()).thenReturn("gemini-3.5-flash-lite");
        when(geminiClient.generateStructuredContent(anyString(), anyString()))
                .thenReturn(Optional.of(geminiJson));

        AiReviewAgentService.ReviewEvaluationResult result = agentService.evaluateBooking(event, profile);

        assertNotNull(result);
        assertEquals(AiReviewRecommendation.RECOMMEND_APPROVAL, result.recommendation());
        assertEquals(0.92, result.confidenceScore());
        assertTrue(result.rationale().contains("82%"));
        assertTrue(result.isDemonstrationData());
        assertEquals("gemini-3.5-flash-lite", result.modelName());
    }

    @Test
    void testEvaluateBooking_NonPunitiveGuard_OverridesRejectionWhenTelemetryUnavailable() {
        // BOOK-105: Missing or unavailable telemetry must NEVER justify rejection
        UUID bookingId = UUID.randomUUID();
        BookingReviewEvent event = createEvent(bookingId);
        // Requester has 0 cancellations, but telemetry is unavailable
        RequesterHistoryProfile profile = createProfile(event.memberId(), 1, 0, null, "UNAVAILABLE");

        String geminiJson = """
            {
                "recommendation": "RECOMMEND_REJECTION",
                "confidenceScore": 0.85,
                "rationale": "Telemetry data was not recorded for previous booking.",
                "supportingEvidence": "No telemetry found.",
                "dataLimitations": "Telemetry unavailable.",
                "isDemonstrationData": true
            }
            """;

        when(geminiClient.isConfigured()).thenReturn(true);
        when(geminiClient.generateStructuredContent(anyString(), anyString()))
                .thenReturn(Optional.of(geminiJson));

        AiReviewAgentService.ReviewEvaluationResult result = agentService.evaluateBooking(event, profile);

        // Must be overridden to NEEDS_HUMAN_REVIEW
        assertEquals(AiReviewRecommendation.NEEDS_HUMAN_REVIEW, result.recommendation());
        assertTrue(result.rationale().contains("Overridden to NEEDS_HUMAN_REVIEW"));
    }

    @Test
    void testEvaluateBooking_GeminiUnconfigured_FallsBackToRuleBased() {
        UUID bookingId = UUID.randomUUID();
        BookingReviewEvent event = createEvent(bookingId);
        RequesterHistoryProfile profile = createProfile(event.memberId(), 3, 0, 75.0, "COMPLETE");

        when(geminiClient.isConfigured()).thenReturn(false);

        AiReviewAgentService.ReviewEvaluationResult result = agentService.evaluateBooking(event, profile);

        assertNotNull(result);
        assertEquals(AiReviewRecommendation.RECOMMEND_APPROVAL, result.recommendation());
        assertEquals("RULE_BASED_FALLBACK", result.modelName());
        assertTrue(result.rationale().contains("75.0%"));
    }

    @Test
    void testPromptContainsBookingLevelMeasurementsAndTimestamps() {
        UUID bookingId = UUID.randomUUID();
        BookingReviewEvent event = createEvent(bookingId);

        UUID pastBookingId = UUID.randomUUID();
        LocalDateTime pastStart = LocalDateTime.of(2026, 10, 10, 14, 0);
        LocalDateTime pastEnd = LocalDateTime.of(2026, 10, 10, 16, 0);

        ExternalTelemetryMeasurement m1 = new ExternalTelemetryMeasurement(
                Instant.parse("2026-10-10T14:15:00Z"), 65.0, 50.0, 60.0, 200.0);
        ExternalTelemetryMeasurement m2 = new ExternalTelemetryMeasurement(
                Instant.parse("2026-10-10T14:45:00Z"), 85.0, 75.0, 68.0, 250.0);

        PastBookingTelemetryRecord pastRecord = new PastBookingTelemetryRecord(
                pastBookingId,
                pastStart,
                pastEnd,
                "COMPLETE",
                100.0,
                false,
                null,
                75.0,
                85.0,
                62.5,
                64.0,
                225.0,
                List.of(m1, m2),
                2,
                true
        );

        RequesterHistoryProfile profile = new RequesterHistoryProfile(
                event.memberId(),
                event.resourceId(),
                "gpu-node-01",
                1,
                1,
                0,
                75.0,
                85.0,
                "COMPLETE",
                new ExternalResourceHealth("gpu-node-01", "HEALTHY", 30.0, 50.0, 65.0, 220.0, Instant.now(), "DEMO", true),
                true,
                "Device-level reservation measurements only. No per-process attribution exists in MRLBS.",
                List.of(pastRecord)
        );

        String prompt = agentService.buildEvaluationPrompt(event, profile);

        assertNotNull(prompt);
        // Verify booking-level context exists
        assertTrue(prompt.contains(pastBookingId.toString()), "Prompt must contain past booking ID");
        assertTrue(prompt.contains("2026-10-10T14:00"), "Prompt must contain past booking start time");
        assertTrue(prompt.contains("Coverage: 100.0%"), "Prompt must contain coverage percentage");

        // Verify individual measurements and timestamps exist
        assertTrue(prompt.contains("2026-10-10T14:15:00Z"), "Prompt must contain measurement timestamp 1");
        assertTrue(prompt.contains("2026-10-10T14:45:00Z"), "Prompt must contain measurement timestamp 2");
        assertTrue(prompt.contains("GPU: 65.0%"), "Prompt must contain individual GPU % measurement");
        assertTrue(prompt.contains("GPU: 85.0%"), "Prompt must contain individual GPU % measurement");
        assertTrue(prompt.contains("Mem: 75.0%"), "Prompt must contain individual Memory % measurement");
        assertTrue(prompt.contains("Temp: 68.0°C"), "Prompt must contain individual Temp measurement");
        assertTrue(prompt.contains("Power: 250.0W"), "Prompt must contain individual Power measurement");

        // Summary statistics supplement rather than replace underlying evidence
        assertTrue(prompt.contains("Avg GPU: 75.0%"), "Prompt must retain summary statistics as supplement");
        assertTrue(prompt.contains("Peak GPU: 85.0%"), "Prompt must retain peak statistics as supplement");
    }

    @Test
    void testTwoHistoriesWithIdenticalAveragesProduceDistinguishablePrompts() {
        // User A: Steady, continuous 50% load
        // User B: Idle 0% followed by burst 100%
        // Both have exact same 50% average and 1 completed booking, but different booking-level patterns
        BookingReviewEvent event = createEvent(UUID.randomUUID());

        Instant t1 = Instant.parse("2026-10-12T10:00:00Z");
        Instant t2 = Instant.parse("2026-10-12T10:30:00Z");

        ExternalTelemetryMeasurement steady1 = new ExternalTelemetryMeasurement(t1, 50.0, 40.0, 55.0, 180.0);
        ExternalTelemetryMeasurement steady2 = new ExternalTelemetryMeasurement(t2, 50.0, 40.0, 55.0, 180.0);
        PastBookingTelemetryRecord steadyRecord = new PastBookingTelemetryRecord(
                UUID.randomUUID(), LocalDateTime.of(2026, 10, 12, 10, 0), LocalDateTime.of(2026, 10, 12, 11, 0),
                "COMPLETE", 100.0, false, null, 50.0, 50.0, 40.0, 55.0, 180.0,
                List.of(steady1, steady2), 2, true
        );
        RequesterHistoryProfile steadyProfile = new RequesterHistoryProfile(
                event.memberId(), event.resourceId(), "gpu-node-01", 1, 1, 0,
                50.0, 50.0, "COMPLETE", null, true, "Device-level measurements only.",
                List.of(steadyRecord)
        );

        ExternalTelemetryMeasurement burst1 = new ExternalTelemetryMeasurement(t1, 0.0, 10.0, 45.0, 90.0);
        ExternalTelemetryMeasurement burst2 = new ExternalTelemetryMeasurement(t2, 100.0, 80.0, 75.0, 300.0);
        PastBookingTelemetryRecord burstRecord = new PastBookingTelemetryRecord(
                UUID.randomUUID(), LocalDateTime.of(2026, 10, 12, 10, 0), LocalDateTime.of(2026, 10, 12, 11, 0),
                "COMPLETE", 100.0, false, null, 50.0, 100.0, 45.0, 60.0, 195.0,
                List.of(burst1, burst2), 2, true
        );
        RequesterHistoryProfile burstProfile = new RequesterHistoryProfile(
                event.memberId(), event.resourceId(), "gpu-node-01", 1, 1, 0,
                50.0, 100.0, "COMPLETE", null, true, "Device-level measurements only.",
                List.of(burstRecord)
        );

        String promptSteady = agentService.buildEvaluationPrompt(event, steadyProfile);
        String promptBurst = agentService.buildEvaluationPrompt(event, burstProfile);

        assertNotEquals(promptSteady, promptBurst, "Two histories with identical averages must remain distinguishable");
        assertTrue(promptSteady.contains("GPU: 50.0%"));
        assertTrue(promptBurst.contains("GPU: 0.0%"));
        assertTrue(promptBurst.contains("GPU: 100.0%"));
    }

    @Test
    void testDeviceLevelTelemetryIsNotMisrepresentedAsIndividualRequesterUsage() {
        BookingReviewEvent event = createEvent(UUID.randomUUID());
        RequesterHistoryProfile profile = createProfile(event.memberId(), 2, 0, 60.0, "COMPLETE");

        String prompt = agentService.buildEvaluationPrompt(event, profile);

        assertTrue(prompt.contains("System Attribution Limitation:"),
                "Prompt must explicitly state attribution limitations");
        assertTrue(prompt.contains("Device-level reservation measurements only"),
                "Prompt must clarify that metrics are hardware device-level, not individual process attribution");
    }

    @Test
    void testCurrentResourceHealthKeptSeparateFromHistoricalTelemetry() {
        BookingReviewEvent event = createEvent(UUID.randomUUID());
        RequesterHistoryProfile profile = createProfile(event.memberId(), 2, 0, 60.0, "COMPLETE");

        String prompt = agentService.buildEvaluationPrompt(event, profile);

        assertTrue(prompt.contains("=== CURRENT RESOURCE HEALTH (Present Snapshot Only) ==="));
        assertTrue(prompt.contains("Never treat as historical evidence or guarantee of future availability"));
        assertTrue(prompt.contains("=== DETAILED HISTORICAL EVIDENCE BY PAST COMPLETED BOOKING ==="));
    }

    @Test
    void testGeminiApiNetworkExceptionHandledSafely() {
        UUID bookingId = UUID.randomUUID();
        BookingReviewEvent event = createEvent(bookingId);
        RequesterHistoryProfile profile = createProfile(event.memberId(), 3, 0, 75.0, "COMPLETE");

        when(geminiClient.isConfigured()).thenReturn(true);
        when(geminiClient.generateStructuredContent(anyString(), anyString()))
                .thenThrow(new RuntimeException("Gemini network timeout"));

        AiReviewAgentService.ReviewEvaluationResult result = agentService.evaluateBooking(event, profile);
        assertNotNull(result, "Should fall back gracefully when Gemini throws exception");
        assertEquals("RULE_BASED_FALLBACK", result.modelName());
        assertEquals(AiReviewRecommendation.RECOMMEND_APPROVAL, result.recommendation());
    }

    @Test
    void testGeminiMalformedNonJsonResponseHandledSafely() {
        UUID bookingId = UUID.randomUUID();
        BookingReviewEvent event = createEvent(bookingId);
        RequesterHistoryProfile profile = createProfile(event.memberId(), 3, 0, 75.0, "COMPLETE");

        when(geminiClient.isConfigured()).thenReturn(true);
        when(geminiClient.generateStructuredContent(anyString(), anyString()))
                .thenReturn(Optional.of("I cannot evaluate this: {{{ not valid json"));

        AiReviewAgentService.ReviewEvaluationResult result = agentService.evaluateBooking(event, profile);
        assertNotNull(result, "Should fall back gracefully when Gemini returns malformed output");
        assertEquals("RULE_BASED_FALLBACK", result.modelName());
        assertEquals(AiReviewRecommendation.RECOMMEND_APPROVAL, result.recommendation());
    }

    @Test
    void testMissingOrIncompleteOrDemoTelemetryCannotJustifyRejection() {
        UUID bookingId = UUID.randomUUID();
        BookingReviewEvent event = createEvent(bookingId);
        RequesterHistoryProfile profile = createProfile(event.memberId(), 1, 0, null, "PARTIAL");

        String badRejectionJson = """
            {
                "recommendation": "RECOMMEND_REJECTION",
                "confidenceScore": 0.90,
                "rationale": "Historical telemetry coverage was incomplete.",
                "supportingEvidence": "Partial coverage only.",
                "dataLimitations": "Partial telemetry fixture.",
                "isDemonstrationData": true
            }
            """;

        when(geminiClient.isConfigured()).thenReturn(true);
        when(geminiClient.generateStructuredContent(anyString(), anyString()))
                .thenReturn(Optional.of(badRejectionJson));

        AiReviewAgentService.ReviewEvaluationResult result = agentService.evaluateBooking(event, profile);

        // Safety Guard must override RECOMMEND_REJECTION to NEEDS_HUMAN_REVIEW
        assertEquals(AiReviewRecommendation.NEEDS_HUMAN_REVIEW, result.recommendation());
        assertTrue(result.rationale().contains("Overridden to NEEDS_HUMAN_REVIEW"));
    }
}
