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
    @Query("SELECT b FROM Booking b LEFT JOIN FETCH b.resource WHERE b.memberId = :memberId ORDER BY b.startTime DESC")
    List<Booking> findByMemberIdOrderByStartTimeDesc(@Param("memberId") UUID memberId);

    List<Booking> findByStatusOrderByStartTimeAsc(BookingStatus status);

    boolean existsByIdAndResource_TypeIgnoreCase(UUID id, String type);

    interface AvailabilityWindow {
        UUID getResourceId();
        LocalDateTime getStartTime();
        LocalDateTime getEndTime();
    }

    @Query("""
            SELECT b.resource.id AS resourceId, b.startTime AS startTime, b.endTime AS endTime
            FROM Booking b
            WHERE b.resource.id IN :resourceIds AND b.status = :status
                AND b.startTime < :end AND b.endTime > :start
            ORDER BY b.startTime
            """)
    List<AvailabilityWindow> findAvailabilityWindows(
            @Param("resourceIds") List<UUID> resourceIds,
            @Param("status") BookingStatus status,
            @Param("start") LocalDateTime start,
            @Param("end") LocalDateTime end);

    List<Booking> findByResourceIdAndStatusAndStartTimeLessThanAndEndTimeGreaterThanOrderByStartTimeAsc(
            UUID resourceId, BookingStatus status, LocalDateTime before, LocalDateTime after);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT b FROM Booking b WHERE b.id = :id")
    Optional<Booking> findByIdForUpdate(@Param("id") UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT b FROM Booking b WHERE b.resource.id = :resourceId AND b.status IN (com.mrlbs.booking.BookingStatus.CONFIRMED, com.mrlbs.booking.BookingStatus.IN_PROGRESS) AND b.startTime < :end AND b.endTime > :start")
    List<Booking> findOverlappingConfirmedForUpdate(
            @Param("resourceId") UUID resourceId,
            @Param("start") LocalDateTime start,
            @Param("end") LocalDateTime end);

    @Query("SELECT b FROM Booking b WHERE b.resource.id = :resourceId AND b.status IN (com.mrlbs.booking.BookingStatus.CONFIRMED, com.mrlbs.booking.BookingStatus.IN_PROGRESS) AND b.startTime < :end AND b.endTime > :start")
    List<Booking> findOverlappingConfirmed(
            @Param("resourceId") UUID resourceId,
            @Param("start") LocalDateTime start,
            @Param("end") LocalDateTime end);

    @Query("SELECT b FROM Booking b WHERE b.resource.id = :resourceId AND b.status IN (com.mrlbs.booking.BookingStatus.CONFIRMED, com.mrlbs.booking.BookingStatus.IN_PROGRESS) AND b.startTime < :bufferEnd AND b.endTime > :bufferStart")
    List<Booking> findConfirmedInWindow(
            @Param("resourceId") UUID resourceId,
            @Param("bufferStart") LocalDateTime bufferStart,
            @Param("bufferEnd") LocalDateTime bufferEnd);

    @Query("SELECT COUNT(b) FROM Booking b WHERE b.memberId = :memberId AND b.resource.id = :resourceId AND b.status IN :statuses")
    long countActiveBookingsForMember(
            @Param("memberId") UUID memberId,
            @Param("resourceId") UUID resourceId,
            @Param("statuses") List<BookingStatus> statuses);

    @Query("SELECT DISTINCT b.resource.id FROM Booking b WHERE b.status IN (com.mrlbs.booking.BookingStatus.CONFIRMED, com.mrlbs.booking.BookingStatus.IN_PROGRESS) AND b.startTime < :end AND b.endTime > :start")
    List<UUID> findBookedResourceIdsInSlot(
            @Param("start") LocalDateTime start,
            @Param("end") LocalDateTime end);
}
