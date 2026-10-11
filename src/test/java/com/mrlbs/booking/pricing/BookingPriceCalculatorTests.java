package com.mrlbs.booking.pricing;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BookingPriceCalculatorTests {
    private final BookingPriceCalculator calculator = new BookingPriceCalculator();
    private final PricingPolicy policy = new PricingPolicy(new BigDecimal("1.0"), new BigDecimal("3.0"));

    @Test
    void roundsShortDurationUpToConfiguredFloorBeforePricing() {
        PriceQuote quote = calculator.calculate(Duration.ofMinutes(20), new BigDecimal("10"),
                BigDecimal.ONE, BigDecimal.ONE, policy);

        assertEquals(new BigDecimal("0.33333333"), quote.requestedDurationHours());
        assertEquals(new BigDecimal("1.0"), quote.billableDurationHours());
        assertEquals(new BigDecimal("10"), quote.totalCredits());
    }

    @Test
    void doesNotRoundDurationsAtOrAboveFloorDown() {
        PriceQuote quote = calculator.calculate(Duration.ofMinutes(90), new BigDecimal("10"),
                BigDecimal.ONE, BigDecimal.ONE, policy);

        assertEquals(new BigDecimal("1.50000000"), quote.billableDurationHours());
        assertEquals(new BigDecimal("15"), quote.totalCredits());
    }

    @Test
    void capsContentionMultiplierBeforeCalculatingAndRoundsCreditsUp() {
        PriceQuote quote = calculator.calculate(Duration.ofHours(1), new BigDecimal("2.1"),
                BigDecimal.ONE, new BigDecimal("9"), policy);

        assertEquals(new BigDecimal("3.0"), quote.appliedContentionMultiplier());
        assertEquals(new BigDecimal("7"), quote.totalCredits());
    }

    @Test
    void leavesContentionMultiplierBelowCapUnchanged() {
        PriceQuote quote = calculator.calculate(Duration.ofHours(1), new BigDecimal("2"),
                new BigDecimal("1.5"), new BigDecimal("2"), policy);

        assertEquals(new BigDecimal("2"), quote.appliedContentionMultiplier());
        assertEquals(new BigDecimal("6"), quote.totalCredits());
    }

    @Test
    void rejectsZeroOrNegativeDurationAndNegativeMultipliers() {
        assertThrows(IllegalArgumentException.class, () -> calculator.calculate(Duration.ZERO,
                BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, policy));
        assertThrows(IllegalArgumentException.class, () -> calculator.calculate(Duration.ofHours(-1),
                BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, policy));
        assertThrows(IllegalArgumentException.class, () -> calculator.calculate(Duration.ofHours(1),
                BigDecimal.ONE, BigDecimal.ONE, new BigDecimal("-1"), policy));
    }

    @Test
    void rejectsInvalidPricingPolicy() {
        assertThrows(IllegalArgumentException.class,
                () -> new PricingPolicy(BigDecimal.ZERO, BigDecimal.ONE));
        assertThrows(IllegalArgumentException.class,
                () -> new PricingPolicy(BigDecimal.ONE, new BigDecimal("-0.1")));
    }

    @Test
    void computesFromDurationAtSecondPrecisionWithoutDroppingFractionalHours() {
        PriceQuote quote = calculator.calculate(Duration.ofSeconds(3601), new BigDecimal("1"),
                BigDecimal.ONE, BigDecimal.ONE, new PricingPolicy(new BigDecimal("0.01"), new BigDecimal("3")));

        assertEquals(new BigDecimal("1.00027778"), quote.billableDurationHours());
        assertEquals(new BigDecimal("2"), quote.totalCredits());
    }
}
