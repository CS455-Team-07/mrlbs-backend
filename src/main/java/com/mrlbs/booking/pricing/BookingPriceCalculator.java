package com.mrlbs.booking.pricing;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.Objects;

/** Implements the application-layer dynamic credit pricing formula. */
public class BookingPriceCalculator {
    public PriceQuote calculate(
            Duration requestedDuration,
            BigDecimal baseRate,
            BigDecimal timeMultiplier,
            BigDecimal contentionMultiplier,
            PricingPolicy policy) {
        Objects.requireNonNull(requestedDuration, "Duration is required");
        Objects.requireNonNull(baseRate, "Base rate is required");
        Objects.requireNonNull(timeMultiplier, "Time multiplier is required");
        Objects.requireNonNull(contentionMultiplier, "Contention multiplier is required");
        Objects.requireNonNull(policy, "Pricing policy is required");
        if (requestedDuration.isNegative() || requestedDuration.isZero()) {
            throw new IllegalArgumentException("Duration must be positive");
        }
        requireNonNegative(baseRate, "Base rate");
        requireNonNegative(timeMultiplier, "Time multiplier");
        requireNonNegative(contentionMultiplier, "Contention multiplier");

        BigDecimal actualHours = BigDecimal.valueOf(requestedDuration.toSeconds())
                .divide(BigDecimal.valueOf(3600), 8, RoundingMode.HALF_UP);
        BigDecimal billableHours = actualHours.max(policy.minimumDurationHours());
        BigDecimal appliedContention = contentionMultiplier.min(policy.maximumContentionMultiplier());
        BigDecimal credits = billableHours.multiply(baseRate)
                .multiply(timeMultiplier)
                .multiply(appliedContention)
                .setScale(0, RoundingMode.CEILING);

        return new PriceQuote(actualHours, billableHours, timeMultiplier, appliedContention, credits);
    }

    private static void requireNonNegative(BigDecimal value, String label) {
        if (value.signum() < 0) {
            throw new IllegalArgumentException(label + " cannot be negative");
        }
    }
}
