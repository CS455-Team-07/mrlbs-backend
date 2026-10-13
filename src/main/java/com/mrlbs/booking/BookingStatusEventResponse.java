package com.mrlbs.booking;

import java.time.LocalDateTime;
import java.util.UUID;

public record BookingStatusEventResponse(UUID id, UUID bookingId, BookingStatus fromStatus,
                                         BookingStatus toStatus, String actorEmail,
                                         LocalDateTime occurredAt, String note) {
    public static BookingStatusEventResponse from(BookingStatusEvent event) {
        return new BookingStatusEventResponse(event.getId(), event.getBookingId(), event.getFromStatus(),
                event.getToStatus(), event.getActorEmail(), event.getOccurredAt(), event.getNote());
    }
}
