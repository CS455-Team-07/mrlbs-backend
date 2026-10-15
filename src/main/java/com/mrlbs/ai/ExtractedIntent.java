package com.mrlbs.ai;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.time.LocalTime;

public record ExtractedIntent(
        @Size(max = 100) String resourceType,
        LocalDate date,
        LocalTime startTime,
        LocalTime endTime,
        @Positive Integer minCapacity,
        @Size(max = 500) String clarifyingQuestion
) {
    public boolean hasDate() {
        return date != null;
    }

    public boolean isComplete() {
        return hasDate() && (clarifyingQuestion == null || clarifyingQuestion.isBlank());
    }

    @AssertTrue(message = "endTime must be after startTime")
    public boolean isTimeRangeValid() {
        return startTime == null || endTime == null || endTime.isAfter(startTime);
    }
}
