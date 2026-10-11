package com.mrlbs.booking;

import jakarta.validation.constraints.NotNull;

import java.time.LocalDateTime;
import java.util.UUID;

public record BookingRequest(@NotNull UUID resourceId,
                             @NotNull LocalDateTime startTime,
                             @NotNull LocalDateTime endTime) {
}
