package com.mrlbs.booking;

import jakarta.validation.constraints.NotNull;

import java.time.LocalDateTime;

public record BookingTimeWindowRequest(@NotNull LocalDateTime startTime,
                                       @NotNull LocalDateTime endTime) {
}
