package com.mrlbs.resource;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record ResourceAvailabilityResponse(UUID resourceId, LocalDate date,
                                          List<TimeSlot> bookedSlots,
                                          List<TimeSlot> availableSlots) {
    public record TimeSlot(LocalDateTime startTime, LocalDateTime endTime) { }
}
