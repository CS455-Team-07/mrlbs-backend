package com.mrlbs.booking;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface BookingStatusEventRepository extends JpaRepository<BookingStatusEvent, UUID> {
    List<BookingStatusEvent> findByBookingIdOrderByOccurredAtAsc(UUID bookingId);
}
