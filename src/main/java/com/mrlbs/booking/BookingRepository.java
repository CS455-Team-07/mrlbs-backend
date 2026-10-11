package com.mrlbs.booking;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

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

    List<Booking> findByStatusOrderByStartTimeAsc(BookingStatus status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select booking from Booking booking where booking.id = :id and booking.ownerEmail = :ownerEmail")
    Optional<Booking> findOwnedBookingForUpdate(@Param("id") UUID id, @Param("ownerEmail") String ownerEmail);

    Optional<Booking> findFirstByResourceIdAndStatusAndStartTimeLessThanAndEndTimeGreaterThanAndIdNot(
            UUID resourceId, BookingStatus status, LocalDateTime before, LocalDateTime after, UUID excludedBookingId);
}
