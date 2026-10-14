package com.mrlbs.ai.review;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BookingAiReviewRepository extends JpaRepository<BookingAiReview, UUID> {
    Optional<BookingAiReview> findByBookingId(UUID bookingId);
    List<BookingAiReview> findByBookingIdIn(Collection<UUID> bookingIds);
    boolean existsByBookingId(UUID bookingId);
}
