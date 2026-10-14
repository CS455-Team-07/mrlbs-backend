package com.mrlbs.booking;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

public record BookingResponse(UUID id, UUID resourceId, String resourceName, LocalDateTime startTime, LocalDateTime endTime,
                              BookingStatus status, BigDecimal creditCost, Boolean clashes, UUID memberId) {
    public static BookingResponse from(Booking booking) {
        return from(booking, false);
    }

    public static BookingResponse from(Booking booking, boolean clashes) {
        String resourceName = null;
        UUID resourceId = null;
        if (booking.getResource() != null) {
            try {
                resourceId = booking.getResource().getId();
                resourceName = booking.getResource().getName();
            } catch (Exception ignored) {
                resourceName = "Archived Resource";
            }
        }
        return new BookingResponse(booking.getId(),
                resourceId,
                resourceName != null ? resourceName : "Archived Resource",
                booking.getStartTime(),
                booking.getEndTime(),
                booking.getStatus(),
                booking.getCreditCost(),
                clashes,
                booking.getMemberId());
    }
}
