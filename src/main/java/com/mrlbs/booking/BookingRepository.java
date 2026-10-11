
package com.mrlbs.booking;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

public interface BookingRepository extends JpaRepository<Booking, Long> {

    // BOOK-80: Check whether a resource has a conflicting booking.
    @Query("""
        SELECT CASE WHEN COUNT(b) > 0 THEN true ELSE false END
        FROM Booking b
        WHERE b.resource.id = :resourceId
          AND b.status IN :statuses
          AND b.startTime < :requestedEnd
          AND b.endTime > :requestedStart
        """)
    boolean existsOverlappingBooking(
        @Param("resourceId") Long resourceId,
        @Param("requestedStart") LocalDateTime requestedStart,
        @Param("requestedEnd") LocalDateTime requestedEnd,
        @Param("statuses") Collection<BookingStatus> statuses
    );

    // BOOK-75: Retrieve all bookings for a resource.
    List<Booking> findByResourceId(Long resourceId);

    // BOOK-75: Retrieve bookings by resource and status.
    List<Booking> findByResourceIdAndStatus(
        Long resourceId,
        BookingStatus status
    );
}
