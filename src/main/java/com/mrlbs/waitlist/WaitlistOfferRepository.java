package com.mrlbs.waitlist;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WaitlistOfferRepository extends JpaRepository<WaitlistOffer, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT o FROM WaitlistOffer o WHERE o.id = :id")
    Optional<WaitlistOffer> findByIdForUpdate(@Param("id") UUID id);

    List<WaitlistOffer> findByWaitlistEntryId(UUID waitlistEntryId);

    @Query("SELECT o FROM WaitlistOffer o JOIN FETCH o.waitlistEntry w LEFT JOIN FETCH w.resource r WHERE w.memberId = :memberId ORDER BY o.offeredAt DESC")
    List<WaitlistOffer> findByMemberId(@Param("memberId") UUID memberId);

    @Query("SELECT o FROM WaitlistOffer o JOIN FETCH o.waitlistEntry w LEFT JOIN FETCH w.resource r WHERE w.memberId = :memberId AND o.status = 'PENDING' AND o.expiresAt > :now ORDER BY o.offeredAt DESC")
    List<WaitlistOffer> findActivePendingOffersForMember(@Param("memberId") UUID memberId, @Param("now") LocalDateTime now);

    @Query("SELECT o FROM WaitlistOffer o WHERE o.waitlistEntry.resource.id = :resourceId AND o.status = 'PENDING' AND o.expiresAt > :now AND o.waitlistEntry.startTime < :end AND o.waitlistEntry.endTime > :start")
    List<WaitlistOffer> findActiveOffersForSlot(
            @Param("resourceId") UUID resourceId,
            @Param("start") LocalDateTime start,
            @Param("end") LocalDateTime end,
            @Param("now") LocalDateTime now);

    Optional<WaitlistOffer> findByResultingBookingId(UUID resultingBookingId);

    @Query("SELECT o FROM WaitlistOffer o JOIN FETCH o.waitlistEntry w LEFT JOIN FETCH w.resource r WHERE o.status = 'PENDING' AND o.expiresAt <= :now")
    List<WaitlistOffer> findExpiredPendingOffers(@Param("now") LocalDateTime now);
}
