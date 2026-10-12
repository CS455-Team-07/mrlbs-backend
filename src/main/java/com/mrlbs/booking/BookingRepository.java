package com.mrlbs.booking;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT b FROM Booking b WHERE b.resource.id = :resourceId AND b.status = com.mrlbs.booking.BookingStatus.CONFIRMED AND b.startTime < :end AND b.endTime > :start")
    List<Booking> findOverlappingConfirmedForUpdate(
            @Param("resourceId") UUID resourceId,
            @Param("start") LocalDateTime start,
            @Param("end") LocalDateTime end);

    @Query("SELECT b FROM Booking b WHERE b.resource.id = :resourceId AND b.status = com.mrlbs.booking.BookingStatus.CONFIRMED AND b.startTime < :end AND b.endTime > :start")
    List<Booking> findOverlappingConfirmed(
            @Param("resourceId") UUID resourceId,
            @Param("start") LocalDateTime start,
            @Param("end") LocalDateTime end);

    @Query("SELECT b FROM Booking b WHERE b.resource.id = :resourceId AND b.status = com.mrlbs.booking.BookingStatus.CONFIRMED AND b.startTime < :bufferEnd AND b.endTime > :bufferStart")
    List<Booking> findConfirmedInWindow(
            @Param("resourceId") UUID resourceId,
            @Param("bufferStart") LocalDateTime bufferStart,
            @Param("bufferEnd") LocalDateTime bufferEnd);

    @Query("SELECT COUNT(b) FROM Booking b WHERE b.ownerEmail = :ownerEmail AND b.resource.id = :resourceId AND b.status IN :statuses")
    long countActiveBookingsForMember(
            @Param("ownerEmail") String ownerEmail,
            @Param("resourceId") UUID resourceId,
            @Param("statuses") List<BookingStatus> statuses);
}
