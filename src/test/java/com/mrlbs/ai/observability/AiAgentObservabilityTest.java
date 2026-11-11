package com.mrlbs.ai.observability;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mrlbs.ai.AiPromptRequest;
import com.mrlbs.ai.AiRecommendationResponse;
import com.mrlbs.ai.AiRecommendationService;
import com.mrlbs.ai.ExtractedIntent;
import com.mrlbs.ai.GeminiClient;
import com.mrlbs.ai.review.AiReviewAgentService;
import com.mrlbs.ai.review.AiReviewRecommendation;
import com.mrlbs.ai.review.BookingAiReview;
import com.mrlbs.ai.review.BookingAiReviewConsumer;
import com.mrlbs.ai.review.BookingAiReviewRepository;
import com.mrlbs.ai.review.BookingReviewEvent;
import com.mrlbs.booking.BookingRepository;
import com.mrlbs.resource.LabResource;
import com.mrlbs.resource.LabResourceRepository;
import com.mrlbs.telemetry.analyzer.RequesterHistoryAnalyzer;
import com.mrlbs.telemetry.analyzer.RequesterHistoryProfile;
import com.mrlbs.telemetry.client.ExternalHistoricalTelemetry;
import com.mrlbs.telemetry.client.ExternalResourceHealth;
import com.mrlbs.telemetry.client.ResourceTelemetryClient;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Automated test suite verifying AI Agent Observability and Diagnostic Logging (NFR-8.2 / US-10).
 */
class AiAgentObservabilityTest {

    private ListAppender<ILoggingEvent> listAppender;
    private Logger observabilityLogger;
    private AiAgentLogger aiAgentLogger;
    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());

    @BeforeEach
    void setUp() {
        observabilityLogger = (Logger) LoggerFactory.getLogger("com.mrlbs.ai.observability");
        listAppender = new ListAppender<>();
        listAppender.start();
        observabilityLogger.addAppender(listAppender);
        aiAgentLogger = new AiAgentLogger();
        MDC.clear();
    }

    @AfterEach
    void tearDown() {
        if (!listAppender.list.isEmpty()) {
            System.out.println("\n--- [ACTUAL CAPTURED AI OBSERVABILITY LOGS] ---");
            for (ILoggingEvent event : listAppender.list) {
                System.out.println(String.format("[%s] %s - %s", event.getLevel(), event.getLoggerName(), event.getFormattedMessage()));
            }
            System.out.println("--- [END OF LOGS] ---\n");
        }
        if (observabilityLogger != null && listAppender != null) {
            observabilityLogger.detachAppender(listAppender);
        }
        MDC.clear();
    }

    private List<String> getLogMessages() {
        return listAppender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    @Nested
    @DisplayName("Security & Redaction Verification")
    class SecurityAndRedactionTests {

        @Test
        @DisplayName("Redacts API keys, passwords, bearer tokens, connection credentials, and masks emails")
        void testSanitizationOfSensitiveData() {
            String rawMessage = "Failed with apiKey: AIzaSyD9876543210123456789012345678901 and password=superSecretPassword! " +
                    "Header: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9. " +
                    "Database: postgres://mrlbs_user:dbPassword123@neon-host:5432/mrlbs_db. " +
                    "Contact: student.researcher@iitk.ac.in for support.";

            String sanitized = AiLogSanitizer.sanitize(rawMessage);

            assertThat(sanitized).doesNotContain("AIzaSyD9876543210123456789012345678901");
            assertThat(sanitized).doesNotContain("superSecretPassword!");
            assertThat(sanitized).doesNotContain("eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9");
            assertThat(sanitized).doesNotContain("dbPassword123");
            assertThat(sanitized).doesNotContain("student.researcher@iitk.ac.in");

            assertThat(sanitized).contains("[REDACTED_GEMINI_KEY]");
            assertThat(sanitized).contains("password=[REDACTED]");
            assertThat(sanitized).contains("Bearer [REDACTED]");
            assertThat(sanitized).contains("postgres://[REDACTED_USER]:[REDACTED_SECRET]@");
            assertThat(sanitized).contains("s***@iitk.ac.in");
        }

        @Test
        @DisplayName("Truncates excessively large responses without leaking raw payload")
        void testPayloadTruncation() {
            String hugePayload = "A".repeat(1000);
            String truncated = AiLogSanitizer.truncate(hugePayload, 100);

            assertThat(truncated).hasSizeLessThan(150);
            assertThat(truncated).contains("[TRUNCATED, len=1000]");
        }

        @Test
        @DisplayName("AiAgentLogger redacts sensitive values inside structured metadata maps")
        void testMetadataRedaction() {
            aiAgentLogger.logAgentStart("TEST_AGENT", "corr-123", "gemini-1.5-flash",
                    Map.of("secretKey", "password=sensitive12345", "userEmail", "admin@iitk.ac.in"));

            List<String> logs = getLogMessages();
            assertThat(logs).hasSize(1);
            String log = logs.get(0);

            assertThat(log).contains("[AI_OBSERVABILITY] event=AGENT_START");
            assertThat(log).doesNotContain("sensitive12345");
            assertThat(log).doesNotContain("admin@iitk.ac.in");
            assertThat(log).contains("a***@iitk.ac.in");
        }
    }

    @Nested
    @DisplayName("AI Request Review Agent Observability")
    class ReviewAgentObservabilityTests {

        @Test
        @DisplayName("Logs complete review lifecycle: AGENT_START with evidence metadata, Gemini tool call, and AGENT_COMPLETE")
        void testReviewAgentSuccessfulLifecycle() {
            GeminiClient geminiClient = mock(GeminiClient.class);
            when(geminiClient.isConfigured()).thenReturn(true);
            when(geminiClient.getModelName()).thenReturn("gemini-1.5-flash");
            when(geminiClient.generateStructuredContent(anyString(), anyString())).thenReturn(Optional.of("""
                    {
                      "recommendation": "RECOMMEND_APPROVAL",
                      "confidenceScore": 0.92,
                      "rationale": "High historical utilization and healthy device state.",
                      "supportingEvidence": "Average GPU 75%, completed 3 past bookings.",
                      "dataLimitations": "Telemetry is device-level snapshot.",
                      "isDemonstrationData": false
                    }
                    """));

            AiReviewAgentService service = new AiReviewAgentService(geminiClient, objectMapper, aiAgentLogger);

            UUID bookingId = UUID.randomUUID();
            UUID memberId = UUID.randomUUID();
            UUID resourceId = UUID.randomUUID();
            String correlationId = "corr-review-success";

            BookingReviewEvent event = new BookingReviewEvent(
                    bookingId, memberId, resourceId, "NVIDIA DGX A100", "GPU",
                    LocalDateTime.now().plusDays(1), LocalDateTime.now().plusDays(1).plusHours(2),
                    correlationId
            );

            RequesterHistoryProfile profile = new RequesterHistoryProfile(
                    memberId, resourceId, "EXT-GPU-01",
                    4, 3, 1, 0, 0,
                    75.0, 95.0, "COMPLETE",
                    new ExternalResourceHealth("EXT-GPU-01", "HEALTHY", 45.0, 30.0, 62.0, 250.0, Instant.now(), "live-sensor", false),
                    true, "Device-level telemetry only", List.of()
            );

            AiReviewAgentService.ReviewEvaluationResult result = service.evaluateBooking(event, profile);

            assertThat(result.recommendation()).isEqualTo(AiReviewRecommendation.RECOMMEND_APPROVAL);

            List<String> logs = getLogMessages();
            assertThat(logs).anyMatch(l -> l.contains("event=AGENT_START") && l.contains("agent=AI_REQUEST_REVIEW_AGENT") &&
                    l.contains("correlationId=corr-review-success") && l.contains("telemetryQuality=COMPLETE"));
            assertThat(logs).anyMatch(l -> l.contains("event=AGENT_COMPLETE") && l.contains("outcome=RECOMMEND_APPROVAL") &&
                    l.contains("confidence=0.92"));
        }

        @Test
        @DisplayName("Logs Gemini failure and fallback decision when model is offline or unparseable")
        void testReviewAgentFallbackDecisionLogging() {
            GeminiClient geminiClient = mock(GeminiClient.class);
            when(geminiClient.isConfigured()).thenReturn(true);
            when(geminiClient.getModelName()).thenReturn("gemini-1.5-flash");
            // Model returns malformed JSON
            when(geminiClient.generateStructuredContent(anyString(), anyString())).thenReturn(Optional.of("MALFORMED_NON_JSON_RESPONSE"));

            AiReviewAgentService service = new AiReviewAgentService(geminiClient, objectMapper, aiAgentLogger);

            UUID bookingId = UUID.randomUUID();
            UUID memberId = UUID.randomUUID();
            UUID resourceId = UUID.randomUUID();
            String correlationId = "corr-review-fallback";

            BookingReviewEvent event = new BookingReviewEvent(
                    bookingId, memberId, resourceId, "NVIDIA DGX A100", "GPU",
                    LocalDateTime.now().plusDays(1), LocalDateTime.now().plusDays(1).plusHours(2),
                    correlationId
            );

            RequesterHistoryProfile profile = new RequesterHistoryProfile(
                    memberId, resourceId, "EXT-GPU-01",
                    0, 0, 0, 0, 0,
                    null, null, "NO_PRIOR_USAGE",
                    null, false, "No prior usage", List.of()
            );

            AiReviewAgentService.ReviewEvaluationResult result = service.evaluateBooking(event, profile);

            assertThat(result.recommendation()).isEqualTo(AiReviewRecommendation.NEEDS_HUMAN_REVIEW);
            assertThat(result.modelName()).isEqualTo("RULE_BASED_FALLBACK");

            List<String> logs = getLogMessages();
            assertThat(logs).anyMatch(l -> l.contains("event=VALIDATION_FAILURE") && l.contains("JSON_SYNTAX_ERROR"));
            assertThat(logs).anyMatch(l -> l.contains("event=FALLBACK_DECISION") && l.contains("recommendation=NEEDS_HUMAN_REVIEW"));
            assertThat(logs).anyMatch(l -> l.contains("event=AGENT_COMPLETE") && l.contains("model=RULE_BASED_FALLBACK"));
        }

        @Test
        @DisplayName("Logs consumer persistence tool call and correlates across RabbitMQ processing")
        void testBookingAiReviewConsumerLogging() throws Exception {
            RequesterHistoryAnalyzer historyAnalyzer = mock(RequesterHistoryAnalyzer.class);
            AiReviewAgentService agentService = mock(AiReviewAgentService.class);
            BookingAiReviewRepository repository = mock(BookingAiReviewRepository.class);
            Channel channel = mock(Channel.class);

            UUID bookingId = UUID.randomUUID();
            String correlationId = "corr-rabbitmq-123";
            BookingReviewEvent event = new BookingReviewEvent(
                    bookingId, UUID.randomUUID(), UUID.randomUUID(), "Test Resource", "Lab",
                    LocalDateTime.now(), LocalDateTime.now().plusHours(1), correlationId
            );

            when(repository.findByBookingId(bookingId)).thenReturn(Optional.empty());
            when(historyAnalyzer.analyzeRequesterHistory(any(), any(), any())).thenReturn(
                    new RequesterHistoryProfile(UUID.randomUUID(), UUID.randomUUID(), "R-01", 1, 1, 0, 0, 0,
                            50.0, 60.0, "COMPLETE", null, true, "limits", List.of())
            );
            when(agentService.evaluateBooking(any(), any())).thenReturn(
                    new AiReviewAgentService.ReviewEvaluationResult(
                            AiReviewRecommendation.RECOMMEND_APPROVAL, 0.88, "Test rationale", "evidence", "limits", false, "gemini-1.5-flash"
                    )
            );

            BookingAiReviewConsumer consumer = new BookingAiReviewConsumer(
                    historyAnalyzer, agentService, repository, objectMapper, aiAgentLogger
            );

            consumer.processBookingReview(objectMapper.writeValueAsString(event), channel, 101L);

            List<String> logs = getLogMessages();
            assertThat(logs).anyMatch(l -> l.contains("event=TOOL_START") && l.contains("tool=review-repository-save") &&
                    l.contains("correlationId=corr-rabbitmq-123"));
            assertThat(logs).anyMatch(l -> l.contains("event=TOOL_COMPLETE") && l.contains("tool=review-repository-save") &&
                    l.contains("status=SUCCESS"));
            Mockito.verify(channel).basicAck(101L, false);
        }
    }

    @Nested
    @DisplayName("AI Resource Recommendation Agent Observability")
    class RecommendationAgentObservabilityTests {

        @Test
        @DisplayName("Logs AGENT_START, catalog search tool call, and AGENT_COMPLETE with candidates")
        void testRecommendationAgentLifecycle() {
            GeminiClient geminiClient = mock(GeminiClient.class);
            when(geminiClient.getModelName()).thenReturn("gemini-1.5-flash");
            LabResourceRepository resourceRepo = mock(LabResourceRepository.class);
            BookingRepository bookingRepo = mock(BookingRepository.class);

            LabResource resource = new LabResource(
                    "Lecture Hall L-7", "Lecture Hall", "Lecture Hall Complex", 150,
                    List.of(), java.math.BigDecimal.ONE, java.math.BigDecimal.valueOf(3), java.math.BigDecimal.ZERO
            );
            resource.setId(UUID.randomUUID());

            when(resourceRepo.findByActiveTrue()).thenReturn(List.of(resource));
            when(bookingRepo.findBookedResourceIdsInSlot(any(), any())).thenReturn(List.of());

            AiRecommendationService service = new AiRecommendationService(
                    geminiClient, resourceRepo, bookingRepo, objectMapper, aiAgentLogger
            );

            MDC.put(AiAgentLogger.MDC_CORRELATION_ID, "corr-rec-456");

            AiPromptRequest request = new AiPromptRequest(
                    "Lecture hall for tomorrow 10:00 - 12:00 with 100 seats",
                    LocalDate.of(2026, 10, 14),
                    null
            );

            AiRecommendationResponse response = service.getRecommendations(request);

            assertThat(response.topRecommendation()).isNotNull();
            assertThat(response.topRecommendation().name()).isEqualTo("Lecture Hall L-7");

            List<String> logs = getLogMessages();
            assertThat(logs).anyMatch(l -> l.contains("event=AGENT_START") && l.contains("agent=AI_RESOURCE_RECOMMENDATION_AGENT") &&
                    l.contains("correlationId=corr-rec-456"));
            assertThat(logs).anyMatch(l -> l.contains("event=TOOL_START") && l.contains("tool=resource-catalog-lookup"));
            assertThat(logs).anyMatch(l -> l.contains("event=TOOL_COMPLETE") && l.contains("tool=resource-catalog-lookup") &&
                    l.contains("activeCatalogCount=1"));
            assertThat(logs).anyMatch(l -> l.contains("event=AGENT_COMPLETE") && l.contains("outcome=EXACT") &&
                    l.contains("topResource=\"Lecture Hall L-7\""));
        }

        @Test
        @DisplayName("Greeting requests log local deterministic engine rather than LLM model")
        void testGreetingLoggingUsesDeterministicModel() {
            GeminiClient geminiClient = mock(GeminiClient.class);
            when(geminiClient.getModelName()).thenReturn("gemini-3.5-flash-lite");
            when(geminiClient.isConfigured()).thenReturn(false);
            LabResourceRepository resourceRepo = mock(LabResourceRepository.class);
            BookingRepository bookingRepo = mock(BookingRepository.class);

            AiRecommendationService service = new AiRecommendationService(
                    geminiClient, resourceRepo, bookingRepo, objectMapper, aiAgentLogger
            );

            MDC.put(AiAgentLogger.MDC_CORRELATION_ID, "corr-rec-greeting");

            AiPromptRequest request = new AiPromptRequest("Hi", LocalDate.now(), null);
            AiRecommendationResponse response = service.getRecommendations(request);

            assertThat(response.followUpQuestion()).isNotNull();

            List<String> logs = getLogMessages();
            assertThat(logs).anyMatch(l -> l.contains("event=AGENT_START") && l.contains("model=local-deterministic-greeting") &&
                    l.contains("correlationId=corr-rec-greeting"));
            assertThat(logs).anyMatch(l -> l.contains("event=AGENT_COMPLETE") && l.contains("outcome=GREETING") &&
                    l.contains("model=local-deterministic-greeting"));
            assertThat(logs).noneMatch(l -> l.contains("model=gemini-3.5-flash-lite"));
        }

        @Test
        @DisplayName("Unconfigured Gemini logs deterministic fallback engine rather than LLM model")
        void testUnconfiguredGeminiLoggingUsesFallbackModel() {
            GeminiClient geminiClient = mock(GeminiClient.class);
            when(geminiClient.getModelName()).thenReturn("gemini-3.5-flash-lite");
            when(geminiClient.isConfigured()).thenReturn(false);
            LabResourceRepository resourceRepo = mock(LabResourceRepository.class);
            BookingRepository bookingRepo = mock(BookingRepository.class);

            AiRecommendationService service = new AiRecommendationService(
                    geminiClient, resourceRepo, bookingRepo, objectMapper, aiAgentLogger
            );

            MDC.put(AiAgentLogger.MDC_CORRELATION_ID, "corr-rec-unconfigured");

            AiPromptRequest request = new AiPromptRequest("booking", null, null);
            AiRecommendationResponse response = service.getRecommendations(request);

            assertThat(response.followUpQuestion()).isNotNull();

            List<String> logs = getLogMessages();
            assertThat(logs).anyMatch(l -> l.contains("event=AGENT_START") && l.contains("model=local-deterministic-fallback (no api key)") &&
                    l.contains("correlationId=corr-rec-unconfigured"));
            assertThat(logs).anyMatch(l -> l.contains("event=AGENT_COMPLETE") && l.contains("outcome=DATE_REQUIRED") &&
                    l.contains("model=local-deterministic-fallback (no api key)"));
            assertThat(logs).noneMatch(l -> l.contains("model=gemini-3.5-flash-lite"));
        }
    }

    @Nested
    @DisplayName("Tool Invocations & Error Handling")
    class ToolInvocationAndErrorTests {

        @Test
        @DisplayName("Logs telemetry health and history tool calls with latency and outcome")
        void testTelemetryClientToolLogging() {
            ResourceTelemetryClient client = new ResourceTelemetryClient("http://invalid-telemetry-host:9999", aiAgentLogger);

            MDC.put(AiAgentLogger.MDC_CORRELATION_ID, "corr-telem-err");

            Optional<ExternalResourceHealth> health = client.getCurrentHealth("GPU-99");
            assertThat(health).isEmpty();

            List<String> logs = getLogMessages();
            assertThat(logs).anyMatch(l -> l.contains("event=TOOL_START") && l.contains("tool=telemetry-current-health") &&
                    l.contains("resourceId=GPU-99"));
            assertThat(logs).anyMatch(l -> l.contains("event=TOOL_FAILURE") && l.contains("tool=telemetry-current-health") &&
                    l.contains("correlationId=corr-telem-err"));
        }

        @Test
        @DisplayName("Logging failures never throw or abort business logic")
        void testLoggingNeverBreaksBusinessFlow() {
            // Test 1: Exception inside AiAgentLogger does not escape
            Map<String, Object> throwingMap = new java.util.HashMap<>() {
                @Override
                public java.util.Set<Entry<String, Object>> entrySet() {
                    throw new RuntimeException("Simulated map serialization failure");
                }
            };

            assertThatCode(() -> {
                aiAgentLogger.logAgentStart("TEST", "id", "model", throwingMap);
                aiAgentLogger.logAgentComplete("TEST", "id", "model", 10, "OUTCOME", "summary", throwingMap);
                aiAgentLogger.logToolStart("TEST", "id", "tool", throwingMap);
                aiAgentLogger.logToolComplete("TEST", "id", "tool", 10, "SUCCESS", throwingMap);
                aiAgentLogger.logAgentFailure("TEST", "id", "model", 10, "ERR", "msg", "fallback");
                aiAgentLogger.logFallbackDecision("TEST", "id", "reason", "rec");
                aiAgentLogger.logValidationFailure("TEST", "id", "reason", "snippet");
            }).doesNotThrowAnyException();

            // Test 2: If logger throws during agent service execution, business evaluation still succeeds
            AiAgentLogger crashingLogger = mock(AiAgentLogger.class);
            Mockito.doThrow(new RuntimeException("Crash logger")).when(crashingLogger)
                    .logAgentStart(any(), any(), any(), any());

            GeminiClient geminiClient = mock(GeminiClient.class);
            when(geminiClient.isConfigured()).thenReturn(false);
            AiReviewAgentService service = new AiReviewAgentService(geminiClient, objectMapper, crashingLogger);

            RequesterHistoryProfile profile = new RequesterHistoryProfile(
                    UUID.randomUUID(), UUID.randomUUID(), "R-1",
                    0, 0, 0, 0, 0, null, null, "NO_PRIOR_USAGE", null, false, "limits", List.of()
            );

            BookingReviewEvent event = new BookingReviewEvent(
                    UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "DGX", "GPU",
                    LocalDateTime.now(), LocalDateTime.now().plusHours(1)
            );

            assertThatCode(() -> {
                AiReviewAgentService.ReviewEvaluationResult result = service.evaluateBooking(event, profile);
                assertThat(result).isNotNull();
                assertThat(result.recommendation()).isEqualTo(AiReviewRecommendation.NEEDS_HUMAN_REVIEW);
            }).doesNotThrowAnyException();
        }
    }
}
