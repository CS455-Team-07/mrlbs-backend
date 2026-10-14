package com.mrlbs.ai.review;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.mrlbs.telemetry.analyzer.RequesterHistoryAnalyzer;
import com.mrlbs.telemetry.analyzer.RequesterHistoryProfile;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BookingAiReviewConsumerTest {

    @Mock
    private RequesterHistoryAnalyzer historyAnalyzer;

    @Mock
    private AiReviewAgentService agentService;

    @Mock
    private BookingAiReviewRepository reviewRepository;

    @Mock
    private Channel channel;

    private ObjectMapper objectMapper;
    private BookingAiReviewConsumer consumer;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        consumer = new BookingAiReviewConsumer(historyAnalyzer, agentService, reviewRepository, objectMapper);
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
    void testProcessBookingReview_Success_PersistsReviewAndAcks() throws Exception {
        UUID bookingId = UUID.randomUUID();
        BookingReviewEvent event = createEvent(bookingId);
        String payload = objectMapper.writeValueAsString(event);
        long deliveryTag = 42L;

        when(reviewRepository.findByBookingId(bookingId)).thenReturn(Optional.empty());

        RequesterHistoryProfile profile = mock(RequesterHistoryProfile.class);
        when(historyAnalyzer.analyzeRequesterHistory(eq(event.memberId()), eq(event.resourceId()), eq(bookingId)))
                .thenReturn(profile);

        AiReviewAgentService.ReviewEvaluationResult evaluationResult =
                new AiReviewAgentService.ReviewEvaluationResult(
                        AiReviewRecommendation.RECOMMEND_APPROVAL,
                        0.95,
                        "High utilization, clean history.",
                        "Evidence summary",
                        "Device-level telemetry limits",
                        true,
                        "gemini-3.5-flash-lite"
                );
        when(agentService.evaluateBooking(eq(event), eq(profile)))
                .thenReturn(evaluationResult);

        consumer.processBookingReview(payload, channel, deliveryTag);

        // Verify review persistence
        ArgumentCaptor<BookingAiReview> reviewCaptor = ArgumentCaptor.forClass(BookingAiReview.class);
        verify(reviewRepository).save(reviewCaptor.capture());
        BookingAiReview saved = reviewCaptor.getValue();
        assertEquals(bookingId, saved.getBookingId());
        assertEquals(AiReviewStatus.COMPLETED, saved.getStatus());
        assertEquals(AiReviewRecommendation.RECOMMEND_APPROVAL, saved.getRecommendation());
        assertEquals(0.95, saved.getConfidenceScore());
        assertEquals("High utilization, clean history.", saved.getRationale());
        assertNotNull(saved.getCompletedAt());

        // Verify consumer separately acknowledged the message to RabbitMQ
        verify(channel).basicAck(eq(deliveryTag), eq(false));
    }

    @Test
    void testProcessBookingReview_DuplicateEvent_AbsorbedSafelyWithoutReprocessing() throws Exception {
        // Idempotency Guard: Publisher crash duplicate event test
        UUID bookingId = UUID.randomUUID();
        BookingReviewEvent event = createEvent(bookingId);
        String payload = objectMapper.writeValueAsString(event);
        long deliveryTag = 43L;

        // Existing review is ALREADY completed!
        BookingAiReview existingCompletedReview = new BookingAiReview(bookingId, AiReviewStatus.COMPLETED);
        existingCompletedReview.setRecommendation(AiReviewRecommendation.RECOMMEND_APPROVAL);
        existingCompletedReview.setRationale("Original review rationale.");
        when(reviewRepository.findByBookingId(bookingId)).thenReturn(Optional.of(existingCompletedReview));

        consumer.processBookingReview(payload, channel, deliveryTag);

        // Must absorb the duplicate: No new Gemini call, no analyzer call, no repository save
        verifyNoInteractions(historyAnalyzer);
        verifyNoInteractions(agentService);
        verify(reviewRepository, never()).save(any());

        // Must still acknowledge the duplicate delivery so broker does not redeliver
        verify(channel).basicAck(eq(deliveryTag), eq(false));
    }

    @Test
    void testProcessBookingReview_ErrorHandling_FallbackHumanOnlyAndAck() throws Exception {
        UUID bookingId = UUID.randomUUID();
        BookingReviewEvent event = createEvent(bookingId);
        String payload = objectMapper.writeValueAsString(event);
        long deliveryTag = 44L;

        when(reviewRepository.findByBookingId(bookingId)).thenReturn(Optional.empty());
        when(historyAnalyzer.analyzeRequesterHistory(any(), any(), any()))
                .thenThrow(new RuntimeException("Simulated unexpected telemetry analysis failure"));

        consumer.processBookingReview(payload, channel, deliveryTag);

        // Should save fallback human only review
        ArgumentCaptor<BookingAiReview> reviewCaptor = ArgumentCaptor.forClass(BookingAiReview.class);
        verify(reviewRepository).save(reviewCaptor.capture());
        BookingAiReview saved = reviewCaptor.getValue();
        assertEquals(AiReviewStatus.FALLBACK_HUMAN_ONLY, saved.getStatus());
        assertEquals(AiReviewRecommendation.NEEDS_HUMAN_REVIEW, saved.getRecommendation());
        assertTrue(saved.getRationale().contains("Simulated unexpected"));

        // Acks message to prevent queue blocking
        verify(channel).basicAck(eq(deliveryTag), eq(false));
    }
}
