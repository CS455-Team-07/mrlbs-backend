package com.mrlbs.ai.review;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mrlbs.telemetry.analyzer.RequesterHistoryAnalyzer;
import com.mrlbs.telemetry.analyzer.RequesterHistoryProfile;
import com.rabbitmq.client.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.Optional;

@Component
public class BookingAiReviewConsumer {

    private static final Logger log = LoggerFactory.getLogger(BookingAiReviewConsumer.class);

    private final RequesterHistoryAnalyzer historyAnalyzer;
    private final AiReviewAgentService agentService;
    private final BookingAiReviewRepository reviewRepository;
    private final ObjectMapper objectMapper;
    private final com.mrlbs.ai.observability.AiAgentLogger aiLogger;

    public BookingAiReviewConsumer(
            RequesterHistoryAnalyzer historyAnalyzer,
            AiReviewAgentService agentService,
            BookingAiReviewRepository reviewRepository,
            ObjectMapper objectMapper
    ) {
        this(historyAnalyzer, agentService, reviewRepository, objectMapper, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public BookingAiReviewConsumer(
            RequesterHistoryAnalyzer historyAnalyzer,
            AiReviewAgentService agentService,
            BookingAiReviewRepository reviewRepository,
            ObjectMapper objectMapper,
            @org.springframework.beans.factory.annotation.Autowired(required = false) com.mrlbs.ai.observability.AiAgentLogger aiLogger
    ) {
        this.historyAnalyzer = historyAnalyzer;
        this.agentService = agentService;
        this.reviewRepository = reviewRepository;
        this.objectMapper = objectMapper;
        this.aiLogger = aiLogger;
    }

    @RabbitListener(
            queues = RabbitMqConfig.QUEUE_NAME,
            containerFactory = "rabbitListenerContainerFactory",
            autoStartup = "${mrlbs.rabbitmq.auto-startup:true}"
    )
    public void processBookingReview(
            @Payload String payload,
            Channel channel,
            @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag
    ) throws IOException {
        BookingReviewEvent event;
        try {
            event = objectMapper.readValue(payload, BookingReviewEvent.class);
        } catch (Exception e) {
            String sanitizedErr = com.mrlbs.ai.observability.AiLogSanitizer.sanitize(e.getMessage());
            log.error("Failed to deserialize booking review event payload: {}. Rejecting message to DLQ: {}",
                    com.mrlbs.ai.observability.AiLogSanitizer.truncate(payload, 200), sanitizedErr);
            channel.basicNack(deliveryTag, false, false);
            return;
        }

        String correlationId = (event.correlationId() != null && !event.correlationId().isBlank())
                ? event.correlationId()
                : java.util.UUID.randomUUID().toString();

        org.slf4j.MDC.put(com.mrlbs.ai.observability.AiAgentLogger.MDC_CORRELATION_ID, correlationId);
        org.slf4j.MDC.put(com.mrlbs.ai.observability.AiAgentLogger.MDC_AGENT, com.mrlbs.ai.observability.AiAgentLogger.AGENT_REVIEW);
        if (event.bookingId() != null) {
            org.slf4j.MDC.put(com.mrlbs.ai.observability.AiAgentLogger.MDC_BOOKING_ID, String.valueOf(event.bookingId()));
        }

        try {
            // 1. Idempotency Guard: Safely handle duplicate deliveries (e.g. publisher crash before outbox status update)
            Optional<BookingAiReview> existingReviewOpt = reviewRepository.findByBookingId(event.bookingId());
            if (existingReviewOpt.isPresent() && existingReviewOpt.get().getStatus() == AiReviewStatus.COMPLETED) {
                log.info("Duplicate review event for already COMPLETED booking {}. Absorbing duplicate and acknowledging.",
                        event.bookingId());
                channel.basicAck(deliveryTag, false);
                return;
            }

            // 2. Analyze requester history and target resource telemetry
            RequesterHistoryProfile profile = historyAnalyzer.analyzeRequesterHistory(
                    event.memberId(),
                    event.resourceId(),
                    event.bookingId()
            );

            // 3. AI Agent advisory evaluation (strictly non-punitive, manual Admin decision preserved)
            AiReviewAgentService.ReviewEvaluationResult result = agentService.evaluateBooking(event, profile);

            // 4. Persist review record (re-fetch by booking ID to guarantee updating managed entity after async evaluation)
            long saveStartNs = System.nanoTime();
            if (aiLogger != null) {
                aiLogger.logToolStart(com.mrlbs.ai.observability.AiAgentLogger.AGENT_REVIEW, correlationId,
                        com.mrlbs.ai.observability.AiAgentLogger.TOOL_PERSISTENCE,
                        java.util.Map.of("bookingId", event.bookingId(), "recommendation", result.recommendation().name()));
            }

            BookingAiReview review = reviewRepository.findByBookingId(event.bookingId())
                    .orElseGet(() -> new BookingAiReview(event.bookingId(), AiReviewStatus.COMPLETED));
            review.setStatus(AiReviewStatus.COMPLETED);
            review.setRecommendation(result.recommendation());
            review.setConfidenceScore(result.confidenceScore());
            review.setRationale(result.rationale());
            review.setSupportingEvidence(result.supportingEvidence());
            review.setDataLimitations(result.dataLimitations());
            review.setDemonstrationData(result.isDemonstrationData());
            review.setModelName(result.modelName());
            review.setErrorMessage(null);
            review.setCompletedAt(LocalDateTime.now());
            reviewRepository.save(review);

            long saveDurationMs = (System.nanoTime() - saveStartNs) / 1_000_000;
            if (aiLogger != null) {
                aiLogger.logToolComplete(com.mrlbs.ai.observability.AiAgentLogger.AGENT_REVIEW, correlationId,
                        com.mrlbs.ai.observability.AiAgentLogger.TOOL_PERSISTENCE, saveDurationMs, "SUCCESS",
                        java.util.Map.of("bookingId", event.bookingId()));
            }

            log.info("Successfully completed AI review for booking {}: Recommendation = {}",
                    event.bookingId(), result.recommendation());

            // 5. Consumer separately acknowledges successful processing to RabbitMQ
            channel.basicAck(deliveryTag, false);

        } catch (Exception e) {
            String sanitizedMsg = com.mrlbs.ai.observability.AiLogSanitizer.sanitize(e.getMessage());
            log.error("Error processing AI review for booking {}: {}. Routing to FALLBACK_HUMAN_ONLY.",
                    event.bookingId(), sanitizedMsg, e);

            if (aiLogger != null) {
                aiLogger.logAgentFailure(com.mrlbs.ai.observability.AiAgentLogger.AGENT_REVIEW, correlationId,
                        "UNKNOWN", 0, e.getClass().getSimpleName(), sanitizedMsg, "FALLBACK_HUMAN_ONLY");
                aiLogger.logFallbackDecision(com.mrlbs.ai.observability.AiAgentLogger.AGENT_REVIEW, correlationId,
                        "Consumer unrecoverable exception: " + sanitizedMsg, "FALLBACK_HUMAN_ONLY");
            }

            // In case of unrecoverable consumer error, mark review as fallback human only and ack so queue is not blocked
            BookingAiReview fallbackReview = reviewRepository.findByBookingId(event.bookingId())
                    .orElseGet(() -> new BookingAiReview(event.bookingId(), AiReviewStatus.FALLBACK_HUMAN_ONLY));
            fallbackReview.setStatus(AiReviewStatus.FALLBACK_HUMAN_ONLY);
            fallbackReview.setRecommendation(AiReviewRecommendation.NEEDS_HUMAN_REVIEW);
            fallbackReview.setConfidenceScore(1.0);
            fallbackReview.setRationale("Manual review: AI evaluation encountered an error (" + sanitizedMsg + ")");
            fallbackReview.setErrorMessage(sanitizedMsg);
            fallbackReview.setCompletedAt(LocalDateTime.now());
            reviewRepository.save(fallbackReview);

            channel.basicAck(deliveryTag, false);
        } finally {
            org.slf4j.MDC.clear();
        }
    }
}
