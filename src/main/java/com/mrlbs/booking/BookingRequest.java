package com.mrlbs.booking;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDateTime;
import java.util.UUID;

public record BookingRequest(@NotNull UUID resourceId,
                             @NotNull LocalDateTime startTime,
                             @NotNull LocalDateTime endTime) {

    @AssertTrue(message = "endTime must be after startTime")
    public boolean isTimeRangeValid() {
        return startTime == null || endTime == null || endTime.isAfter(startTime);
    }
}
