
package com.mrlbs.booking;

import com.mrlbs.resource.BookingPolicy;
import com.mrlbs.resource.BookingRule;
import com.mrlbs.resource.Resource;
import com.mrlbs.resource.ResourceRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

@Service
public class BookingService {

    private final BookingRepository bookingRepository;
    private final ResourceRepository resourceRepository;

    public BookingService(
            BookingRepository bookingRepository,
            ResourceRepository resourceRepository) {
        this.bookingRepository = bookingRepository;
        this.resourceRepository = resourceRepository;
    }

    @Transactional
    public Booking createBooking(
            Long resourceId,
            LocalDateTime startTime,
            LocalDateTime endTime) {

        if (resourceId == null) {
            throw new IllegalArgumentException(
                    "Resource ID is required");
        }

        if (startTime == null || endTime == null
                || !startTime.isBefore(endTime)) {
            throw new IllegalArgumentException(
                    "End time must be after start time");
        }

        // Lock the resource row so simultaneous booking requests
        // for this resource are serialized.
        Resource resource = resourceRepository
                .findByIdForUpdate(resourceId)
                .orElseThrow(() ->
                        new IllegalArgumentException(
                                "Resource not found: " + resourceId));

        if (!resource.isActive()) {
            throw new IllegalArgumentException(
                    "Cannot book an inactive resource");
        }

        // Capture the current rule version for this booking.
        BookingRule rules = resource.getBookingRule();

        if (rules == null) {
            throw new IllegalStateException(
                    "No booking rules are configured for this resource");
        }

        validateRules(rules);

        long durationMinutes =
                Duration.between(startTime, endTime).toMinutes();

        if (durationMinutes < rules.getMinDurationMinutes()) {
            throw new IllegalArgumentException(
                    "Booking duration must be at least "
                    + rules.getMinDurationMinutes() + " minutes");
        }

        if (durationMinutes > rules.getMaxDurationMinutes()) {
            throw new IllegalArgumentException(
                    "Booking duration cannot exceed "
                    + rules.getMaxDurationMinutes() + " minutes");
        }

        // Compare against each existing booking using its own
        // saved rule version and buffer time.
        List<Booking> existingBookings =
                bookingRepository.findByResourceId(resourceId);

        for (Booking existing : existingBookings) {
            if (existing.getStatus() != BookingStatus.PENDING
                    && existing.getStatus() != BookingStatus.CONFIRMED) {
                continue;
            }

            BookingRule existingRules = existing.getBookingRule();

            int existingBuffer = existingRules == null
                    ? 0
                    : existingRules.getBufferTimeMinutes();

            int requestedBuffer = rules.getBufferTimeMinutes();

            LocalDateTime existingBlockedStart =
                    existing.getStartTime().minusMinutes(existingBuffer);

            LocalDateTime existingBlockedEnd =
                    existing.getEndTime().plusMinutes(existingBuffer);

            LocalDateTime requestedBlockedStart =
                    startTime.minusMinutes(requestedBuffer);

            LocalDateTime requestedBlockedEnd =
                    endTime.plusMinutes(requestedBuffer);

            boolean overlaps =
                    requestedBlockedStart.isBefore(existingBlockedEnd)
                    && requestedBlockedEnd.isAfter(existingBlockedStart);

            if (overlaps) {
                throw new IllegalArgumentException(
                        "This resource is already booked or within "
                        + "the required buffer time for that interval");
            }
        }

        Booking booking = new Booking();
        booking.setResource(resource);
        booking.setBookingRule(rules);
        booking.setStartTime(startTime);
        booking.setEndTime(endTime);

        // OPEN bookings are confirmed immediately.
        // APPROVAL_REQUIRED bookings remain pending.
        if (rules.getBookingPolicy() == BookingPolicy.OPEN) {
            booking.setStatus(BookingStatus.CONFIRMED);
        } else {
            booking.setStatus(BookingStatus.PENDING);
        }

        return bookingRepository.save(booking);
    }

    @Transactional
    public Booking confirmBooking(Long bookingId) {
        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() ->
                        new IllegalArgumentException(
                                "Booking not found: " + bookingId));

        if (booking.getStatus() != BookingStatus.PENDING) {
            throw new IllegalStateException(
                    "Only pending bookings can be confirmed");
        }

        // Preserve the original booking-rule reference.
        booking.setStatus(BookingStatus.CONFIRMED);

        return bookingRepository.save(booking);
    }

    public List<Booking> getBookingsForResource(Long resourceId) {
        return bookingRepository.findByResourceId(resourceId);
    }

    private void validateRules(BookingRule rules) {
        if (rules.getBookingPolicy() == null
                || rules.getMinDurationMinutes() == null
                || rules.getMaxDurationMinutes() == null
                || rules.getBufferTimeMinutes() == null
                || rules.getMinDurationMinutes() <= 0
                || rules.getMaxDurationMinutes()
                        < rules.getMinDurationMinutes()
                || rules.getBufferTimeMinutes() < 0) {
            throw new IllegalStateException(
                    "The resource has invalid booking rules");
        }
    }
}
