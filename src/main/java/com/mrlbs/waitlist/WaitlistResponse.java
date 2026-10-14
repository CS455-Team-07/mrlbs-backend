package com.mrlbs.waitlist;

import com.mrlbs.booking.BookingStatus;

import java.time.LocalDateTime;
import java.util.UUID;

public record WaitlistResponse(
        UUID id,
        UUID resourceId,
        String resourceName,
        String resourceType,
        LocalDateTime startTime,
        LocalDateTime endTime,
        BookingStatus status,
        LocalDateTime joinedAt,
        LocalDateTime leftAt,
        Long queuePosition,
        UUID bookingId
) {
    public static WaitlistResponse from(WaitlistEntry entry, Long queuePosition) {
        String resourceName = "Archived Resource";
        String resourceType = "Unknown";
        UUID resourceId = null;
        if (entry.getResource() != null) {
            try {
                resourceId = entry.getResource().getId();
                resourceName = entry.getResource().getName();
                resourceType = entry.getResource().getType();
            } catch (Exception ignored) {
                resourceName = "Archived Resource";
            }
        }
        return new WaitlistResponse(
                entry.getId(),
                resourceId,
                resourceName,
                resourceType,
                entry.getStartTime(),
                entry.getEndTime(),
                entry.getStatus(),
                entry.getJoinedAt(),
                entry.getLeftAt(),
                queuePosition,
                entry.getBookingId()
        );
    }
}
