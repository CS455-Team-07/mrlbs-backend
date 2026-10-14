package com.mrlbs.ai.review;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

@Service
public class BookingEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(BookingEventPublisher.class);

    private final RabbitTemplate rabbitTemplate;
    private final BookingOutboxRepository outboxRepository;
    private final BookingAiReviewRepository reviewRepository;
    private final ObjectMapper objectMapper;

    public BookingEventPublisher(
            RabbitTemplate rabbitTemplate,
            BookingOutboxRepository outboxRepository,
            BookingAiReviewRepository reviewRepository,
            ObjectMapper objectMapper
    ) {
        this.rabbitTemplate = rabbitTemplate;
        this.outboxRepository = outboxRepository;
        this.reviewRepository = reviewRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public BookingOutboxEvent stagePendingApprovalEvent(BookingReviewEvent event) {
        try {
            String jsonPayload = objectMapper.writeValueAsString(event);
            BookingOutboxEvent outboxEvent = new BookingOutboxEvent(
                    event.bookingId(),
                    "BOOKING_PENDING_APPROVAL",
                    jsonPayload
            );
            BookingOutboxEvent saved = outboxRepository.save(outboxEvent);

            // Also create a PENDING BookingAiReview placeholder if not existing
            if (!reviewRepository.existsByBookingId(event.bookingId())) {
                BookingAiReview initialReview = new BookingAiReview(event.bookingId(), AiReviewStatus.PENDING);
                reviewRepository.save(initialReview);
            }

            return saved;
        } catch (Exception e) {
            log.error("Failed to stage outbox event for booking {}: {}", event.bookingId(), e.getMessage());
            throw new IllegalStateException("Could not stage booking review outbox event", e);
        }
    }

    public void publishEventWithConfirm(BookingOutboxEvent outboxEvent) {
        if (outboxEvent == null) return;

        CorrelationData correlationData = new CorrelationData(outboxEvent.getId().toString());

        correlationData.getFuture().whenComplete((confirm, throwable) -> {
            if (throwable != null || confirm == null || !confirm.isAck()) {
                String reason = throwable != null ? throwable.getMessage() : (confirm != null ? confirm.getReason() : "No ACK");
                log.warn("Broker NACK / confirm failure for outbox event {}: {}. Event remains PENDING for retry.",
                        outboxEvent.getId(), reason);
                outboxEvent.setStatus(OutboxStatus.PENDING);
                outboxEvent.setRetryCount(outboxEvent.getRetryCount() + 1);
                outboxRepository.save(outboxEvent);
            } else {
                log.info("RabbitMQ confirmed publication for outbox event {} (booking: {}). Marking SENT.",
                        outboxEvent.getId(), outboxEvent.getBookingId());
                outboxEvent.setStatus(OutboxStatus.SENT);
                outboxEvent.setSentAt(LocalDateTime.now());
                outboxRepository.save(outboxEvent);
            }
        });

        try {
            rabbitTemplate.convertAndSend(
                    RabbitMqConfig.EXCHANGE_NAME,
                    RabbitMqConfig.ROUTING_KEY,
                    outboxEvent.getPayload(),
                    correlationData
            );
        } catch (AmqpException e) {
            log.warn("RabbitMQ connection failure publishing outbox event {} for booking {}: {}. Routing to FALLBACK_HUMAN_ONLY.",
                    outboxEvent.getId(), outboxEvent.getBookingId(), e.getMessage());

            // BOOK-123 Graceful Fallback: Ensure human admin can review immediately without being blocked
            outboxEvent.setStatus(OutboxStatus.PENDING);
            outboxEvent.setRetryCount(outboxEvent.getRetryCount() + 1);
            outboxRepository.save(outboxEvent);

            markFallbackHumanOnly(outboxEvent.getBookingId(), "RabbitMQ broker unavailable (" + e.getClass().getSimpleName() + ")");
        } catch (Exception e) {
            log.error("Unexpected error publishing outbox event {}: {}", outboxEvent.getId(), e.getMessage(), e);
            outboxEvent.setStatus(OutboxStatus.PENDING);
            outboxRepository.save(outboxEvent);
            markFallbackHumanOnly(outboxEvent.getBookingId(), "Unexpected publication error: " + e.getMessage());
        }
    }

    private void markFallbackHumanOnly(UUID bookingId, String reason) {
        Optional<BookingAiReview> reviewOpt = reviewRepository.findByBookingId(bookingId);
        BookingAiReview review = reviewOpt.orElseGet(() -> new BookingAiReview(bookingId, AiReviewStatus.FALLBACK_HUMAN_ONLY));
        review.setStatus(AiReviewStatus.FALLBACK_HUMAN_ONLY);
        review.setRecommendation(AiReviewRecommendation.NEEDS_HUMAN_REVIEW);
        review.setConfidenceScore(1.0);
        review.setRationale("Manual review required: " + reason);
        review.setErrorMessage(reason);
        review.setCompletedAt(LocalDateTime.now());
        reviewRepository.save(review);
    }
}
