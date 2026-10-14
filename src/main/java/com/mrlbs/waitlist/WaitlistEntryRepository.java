package com.mrlbs.waitlist;

import com.mrlbs.booking.BookingStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WaitlistEntryRepository extends JpaRepository<WaitlistEntry, UUID> {

    @Query("SELECT w FROM WaitlistEntry w LEFT JOIN FETCH w.resource WHERE w.memberId = :memberId ORDER BY w.joinedAt DESC")
    List<WaitlistEntry> findByMemberIdOrderByJoinedAtDesc(@Param("memberId") UUID memberId);

    @Query("SELECT w FROM WaitlistEntry w WHERE w.resource.id = :resourceId AND w.status = com.mrlbs.booking.BookingStatus.IN_PROGRESS AND ((w.startTime = :startTime AND w.endTime = :endTime) OR (w.startTime <= :startTime AND w.endTime >= :endTime) OR (w.startTime >= :startTime AND w.endTime <= :endTime)) ORDER BY w.joinedAt ASC, w.id ASC")
    List<WaitlistEntry> findActiveQueue(
            @Param("resourceId") UUID resourceId,
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime);

    @Query("SELECT COUNT(w) > 0 FROM WaitlistEntry w WHERE w.memberId = :memberId AND w.resource.id = :resourceId AND w.status = com.mrlbs.booking.BookingStatus.IN_PROGRESS AND ((w.startTime = :startTime AND w.endTime = :endTime) OR (w.startTime <= :startTime AND w.endTime >= :endTime) OR (w.startTime >= :startTime AND w.endTime <= :endTime))")
    boolean existsActiveWaitlist(
            @Param("memberId") UUID memberId,
            @Param("resourceId") UUID resourceId,
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime);

    boolean existsByMemberIdAndResourceIdAndStartTimeAndEndTimeAndStatus(
            UUID memberId, UUID resourceId, LocalDateTime startTime, LocalDateTime endTime, BookingStatus status);

    Optional<WaitlistEntry> findByBookingId(UUID bookingId);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT w FROM WaitlistEntry w WHERE w.resource.id = :resourceId AND w.status = com.mrlbs.booking.BookingStatus.IN_PROGRESS AND w.startTime < :releasedEnd AND w.endTime > :releasedStart ORDER BY w.joinedAt ASC, w.id ASC")
    List<WaitlistEntry> findCandidatesOverlappingReleasedSlotForUpdate(
            @Param("resourceId") UUID resourceId,
            @Param("releasedStart") LocalDateTime releasedStart,
            @Param("releasedEnd") LocalDateTime releasedEnd);

    @Query("SELECT w FROM WaitlistEntry w WHERE w.resource.id = :resourceId AND w.status = com.mrlbs.booking.BookingStatus.IN_PROGRESS AND w.startTime < :releasedEnd AND w.endTime > :releasedStart ORDER BY w.joinedAt ASC, w.id ASC")
    List<WaitlistEntry> findCandidatesOverlappingReleasedSlot(
            @Param("resourceId") UUID resourceId,
            @Param("releasedStart") LocalDateTime releasedStart,
            @Param("releasedEnd") LocalDateTime releasedEnd);
}
