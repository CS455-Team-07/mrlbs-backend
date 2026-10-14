package com.mrlbs.ai.review;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BookingEventPublisherTest {

    @Mock
    private RabbitTemplate rabbitTemplate;

    @Mock
    private BookingOutboxRepository outboxRepository;

    @Mock
    private BookingAiReviewRepository reviewRepository;

    private ObjectMapper objectMapper;
    private BookingEventPublisher publisher;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        objectMapper = new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
        publisher = new BookingEventPublisher(rabbitTemplate, outboxRepository, reviewRepository, objectMapper);
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

    @Test
    void testStagePendingApprovalEvent_CreatesOutboxAndPlaceholderReview() {
        UUID bookingId = UUID.randomUUID();
        BookingReviewEvent event = createEvent(bookingId);

        when(outboxRepository.save(any(BookingOutboxEvent.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(reviewRepository.existsByBookingId(bookingId)).thenReturn(false);

        BookingOutboxEvent outboxEvent = publisher.stagePendingApprovalEvent(event);

        assertNotNull(outboxEvent);
        assertEquals(bookingId, outboxEvent.getBookingId());
        assertEquals(OutboxStatus.PENDING, outboxEvent.getStatus());
        assertEquals("BOOKING_PENDING_APPROVAL", outboxEvent.getEventType());

        ArgumentCaptor<BookingAiReview> reviewCaptor = ArgumentCaptor.forClass(BookingAiReview.class);
        verify(reviewRepository).save(reviewCaptor.capture());
        assertEquals(bookingId, reviewCaptor.getValue().getBookingId());
        assertEquals(AiReviewStatus.PENDING, reviewCaptor.getValue().getStatus());
    }

    @Test
    void testPublishEventWithConfirm_BrokerAck_MarksSent() {
        UUID bookingId = UUID.randomUUID();
        BookingOutboxEvent outboxEvent = new BookingOutboxEvent(bookingId, "BOOKING_PENDING_APPROVAL", "{}");
        outboxEvent.setId(UUID.randomUUID());

        doAnswer(invocation -> {
            CorrelationData cd = invocation.getArgument(3);
            CorrelationData.Confirm confirm = new CorrelationData.Confirm(true, null);
            cd.getFuture().complete(confirm);
            return null;
        }).when(rabbitTemplate).convertAndSend(eq(RabbitMqConfig.EXCHANGE_NAME), eq(RabbitMqConfig.ROUTING_KEY), anyString(), any(CorrelationData.class));

        publisher.publishEventWithConfirm(outboxEvent);

        assertEquals(OutboxStatus.SENT, outboxEvent.getStatus());
        assertNotNull(outboxEvent.getSentAt());
        verify(outboxRepository).save(outboxEvent);
    }

    @Test
    void testPublishEventWithConfirm_BrokerNack_LeavesPendingForRetry() {
        UUID bookingId = UUID.randomUUID();
        BookingOutboxEvent outboxEvent = new BookingOutboxEvent(bookingId, "BOOKING_PENDING_APPROVAL", "{}");
        outboxEvent.setId(UUID.randomUUID());

        doAnswer(invocation -> {
            CorrelationData cd = invocation.getArgument(3);
            CorrelationData.Confirm confirm = new CorrelationData.Confirm(false, "Broker NACK: queue full");
            cd.getFuture().complete(confirm);
            return null;
        }).when(rabbitTemplate).convertAndSend(eq(RabbitMqConfig.EXCHANGE_NAME), eq(RabbitMqConfig.ROUTING_KEY), anyString(), any(CorrelationData.class));

        publisher.publishEventWithConfirm(outboxEvent);

        assertEquals(OutboxStatus.PENDING, outboxEvent.getStatus());
        assertEquals(1, outboxEvent.getRetryCount());
        assertNull(outboxEvent.getSentAt());
        verify(outboxRepository).save(outboxEvent);
    }

    @Test
    void testPublishEventWithConfirm_BrokerUnavailable_RoutesToFallbackHumanOnly() {
        // BOOK-123 & NFR-3.2: Broker outage test
        UUID bookingId = UUID.randomUUID();
        BookingOutboxEvent outboxEvent = new BookingOutboxEvent(bookingId, "BOOKING_PENDING_APPROVAL", "{}");
        outboxEvent.setId(UUID.randomUUID());

        BookingAiReview placeholderReview = new BookingAiReview(bookingId, AiReviewStatus.PENDING);
        when(reviewRepository.findByBookingId(bookingId)).thenReturn(Optional.of(placeholderReview));

        doThrow(new AmqpConnectException(new Exception("Connection refused: 5672")))
                .when(rabbitTemplate).convertAndSend(eq(RabbitMqConfig.EXCHANGE_NAME), eq(RabbitMqConfig.ROUTING_KEY), anyString(), any(CorrelationData.class));

        // Must not throw an unhandled exception to the caller
        assertDoesNotThrow(() -> publisher.publishEventWithConfirm(outboxEvent));

        // Outbox event preserved for retry
        assertEquals(OutboxStatus.PENDING, outboxEvent.getStatus());
        assertEquals(1, outboxEvent.getRetryCount());
        verify(outboxRepository).save(outboxEvent);

        // Review safely routed to FALLBACK_HUMAN_ONLY for immediate manual Admin review
        ArgumentCaptor<BookingAiReview> reviewCaptor = ArgumentCaptor.forClass(BookingAiReview.class);
        verify(reviewRepository).save(reviewCaptor.capture());
        BookingAiReview savedReview = reviewCaptor.getValue();
        assertEquals(AiReviewStatus.FALLBACK_HUMAN_ONLY, savedReview.getStatus());
        assertEquals(AiReviewRecommendation.NEEDS_HUMAN_REVIEW, savedReview.getRecommendation());
        assertTrue(savedReview.getRationale().contains("RabbitMQ broker unavailable"));
    }
}
