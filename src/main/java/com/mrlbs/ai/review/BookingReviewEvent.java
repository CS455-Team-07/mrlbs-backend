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
    LocalDateTime endTime
) {}
