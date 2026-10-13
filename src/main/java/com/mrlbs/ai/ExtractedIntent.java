package com.mrlbs.ai;

import java.time.LocalDate;
import java.time.LocalTime;

public record ExtractedIntent(
        String resourceType,
        LocalDate date,
        LocalTime startTime,
        LocalTime endTime,
        Integer minCapacity,
        String clarifyingQuestion
) {
    public boolean hasDate() {
        return date != null;
    }

    public boolean isComplete() {
        return hasDate() && (clarifyingQuestion == null || clarifyingQuestion.isBlank());
    }
}
