package com.mrlbs.booking;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;
import java.time.LocalDateTime;
import java.util.Optional;

public interface BookingRepository extends JpaRepository<Booking, UUID> {
    List<Booking> findByOwnerEmailOrderByStartTimeDesc(String ownerEmail);

    List<Booking> findByResourceIdAndStatusAndStartTimeLessThanAndEndTimeGreaterThanOrderByStartTimeAsc(
            UUID resourceId, BookingStatus status, LocalDateTime before, LocalDateTime after);

    Optional<Booking> findFirstByResourceIdAndStatusAndStartTimeLessThanAndEndTimeGreaterThan(
            UUID resourceId, BookingStatus status, LocalDateTime before, LocalDateTime after);
}
