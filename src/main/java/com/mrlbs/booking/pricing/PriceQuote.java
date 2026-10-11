package com.mrlbs.booking.pricing;

import java.math.BigDecimal;

public record PriceQuote(
        BigDecimal requestedDurationHours,
        BigDecimal billableDurationHours,
        BigDecimal timeMultiplier,
        BigDecimal appliedContentionMultiplier,
        BigDecimal totalCredits) {
}
