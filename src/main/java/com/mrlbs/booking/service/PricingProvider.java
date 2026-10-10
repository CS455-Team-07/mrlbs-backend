package com.mrlbs.booking.service;

import java.time.LocalDateTime;
import java.math.BigDecimal;

/**
 * Provides the time‑based multiplier (Mtime) for a booking.
 * The default implementation returns {@code BigDecimal.ONE} indicating no multiplier.
 * In a real system this could be based on peak‑hour schedules.
 */
public interface PricingProvider {
    /**
     * @param start start time of the booking
     * @param end   end time of the booking
     * @return multiplier as BigDecimal (e.g., 1.5 for peak hours)
     */
    BigDecimal getMtimeMultiplier(com.mrlbs.resource.Resource resource, LocalDateTime start, LocalDateTime end);
}
