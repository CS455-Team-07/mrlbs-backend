package com.mrlbs.ai.review;

import java.time.LocalDateTime;
import java.util.UUID;

public record BookingReviewEvent(
    UUID bookingId,
    UUID memberId,
    UUID resourceId,
    String resourceName,
    String resourceType,
    LocalDateTime startTime,
    LocalDateTime endTime,
    String correlationId
) {
    public BookingReviewEvent(
            UUID bookingId,
            UUID memberId,
            UUID resourceId,
            String resourceName,
            String resourceType,
            LocalDateTime startTime,
            LocalDateTime endTime
    ) {
        this(
                bookingId,
                memberId,
                resourceId,
                resourceName,
                resourceType,
                startTime,
                endTime,
                UUID.randomUUID().toString()
        );
    }
}
