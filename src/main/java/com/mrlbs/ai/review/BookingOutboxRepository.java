package com.mrlbs.ai.review;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface BookingOutboxRepository extends JpaRepository<BookingOutboxEvent, UUID> {
    List<BookingOutboxEvent> findByStatusOrderByCreatedAtAsc(OutboxStatus status);
    List<BookingOutboxEvent> findByBookingId(UUID bookingId);
}
