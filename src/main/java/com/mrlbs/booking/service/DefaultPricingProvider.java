package com.mrlbs.booking.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.springframework.stereotype.Service;

/**
 * Default implementation of {@link PricingProvider} that returns a multiplier of 1.
 * This can be replaced with a bean that applies peak‑hour rules.
 */
@Service
public class DefaultPricingProvider implements PricingProvider {
    @Override
    public BigDecimal getMtimeMultiplier(com.mrlbs.resource.Resource resource, LocalDateTime start, LocalDateTime end) {
        java.math.BigDecimal m = resource.getMtimeMultiplier();
        return (m == null) ? java.math.BigDecimal.ONE : m;
    }
}
