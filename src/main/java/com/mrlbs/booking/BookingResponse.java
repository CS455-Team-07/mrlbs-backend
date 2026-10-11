package com.mrlbs.booking;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

public record BookingResponse(UUID id, UUID resourceId, LocalDateTime startTime, LocalDateTime endTime,
                              BookingStatus status, BigDecimal creditCost) {
    public static BookingResponse from(Booking booking) {
        return new BookingResponse(booking.getId(), booking.getResource().getId(), booking.getStartTime(),
                booking.getEndTime(), booking.getStatus(), booking.getCreditCost());
    }
}
