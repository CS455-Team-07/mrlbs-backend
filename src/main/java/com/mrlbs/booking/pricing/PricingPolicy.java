package com.mrlbs.booking.pricing;

import java.math.BigDecimal;

/** Resource-level limits used by the booking price calculation. */
public record PricingPolicy(BigDecimal minimumDurationHours, BigDecimal maximumContentionMultiplier) {
    public PricingPolicy {
        if (minimumDurationHours == null || minimumDurationHours.signum() <= 0) {
            throw new IllegalArgumentException("Minimum duration must be greater than zero");
        }
        if (maximumContentionMultiplier == null || maximumContentionMultiplier.signum() < 0) {
            throw new IllegalArgumentException("Maximum contention multiplier cannot be negative");
        }
    }
}
