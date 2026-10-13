package com.mrlbs.ai;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

public record ResourceRecommendation(
        UUID resourceId,
        String name,
        String type,
        String location,
        Integer capacity,
        BigDecimal baseRate,
        String bookingPolicy,
        String matchType, // "EXACT" or "ALTERNATIVE"
        boolean available,
        LocalDate date,
        LocalTime requestedStartTime,
        LocalTime requestedEndTime,
        LocalTime suggestedStartTime,
        LocalTime suggestedEndTime,
        String explanation,
        List<String> rules
) {}
