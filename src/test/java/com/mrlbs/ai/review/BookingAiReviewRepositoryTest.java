package com.mrlbs.ai.review;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class BookingAiReviewRepositoryTest {

    @Autowired
    private BookingAiReviewRepository reviewRepository;

    @Autowired
    private BookingOutboxRepository outboxRepository;

    @Test
    void testSaveAndFindBookingAiReview() {
        UUID bookingId = UUID.randomUUID();
        BookingAiReview review = new BookingAiReview(bookingId, AiReviewStatus.COMPLETED);
        review.setRecommendation(AiReviewRecommendation.RECOMMEND_APPROVAL);
        review.setConfidenceScore(0.88);
        review.setRationale("Good past usage");
        review.setCompletedAt(LocalDateTime.now());
        reviewRepository.save(review);

        Optional<BookingAiReview> found = reviewRepository.findByBookingId(bookingId);
        assertTrue(found.isPresent());
        assertEquals(AiReviewRecommendation.RECOMMEND_APPROVAL, found.get().getRecommendation());
        assertEquals(0.88, found.get().getConfidenceScore());
        assertEquals(AiReviewStatus.COMPLETED, found.get().getStatus());

        List<BookingAiReview> batch = reviewRepository.findByBookingIdIn(List.of(bookingId, UUID.randomUUID()));
        assertEquals(1, batch.size());
        assertEquals(bookingId, batch.get(0).getBookingId());
    }

    @Test
    void testSaveAndFindBookingOutboxEvent() {
        UUID bookingId = UUID.randomUUID();
        BookingOutboxEvent outbox = new BookingOutboxEvent(bookingId, "BOOKING_PENDING_APPROVAL", "{\"test\":true}");
        outboxRepository.save(outbox);

        List<BookingOutboxEvent> pending = outboxRepository.findByStatusOrderByCreatedAtAsc(OutboxStatus.PENDING);
        assertFalse(pending.isEmpty());
        assertTrue(pending.stream().anyMatch(e -> e.getBookingId().equals(bookingId)));

        List<BookingOutboxEvent> byBooking = outboxRepository.findByBookingId(bookingId);
        assertEquals(1, byBooking.size());
        assertEquals(OutboxStatus.PENDING, byBooking.get(0).getStatus());
    }
}
