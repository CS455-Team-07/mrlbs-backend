package com.mrlbs.booking.service;

import com.mrlbs.booking.Booking;
import com.mrlbs.resource.Resource;
import com.mrlbs.resource.ResourceRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
class BookingServiceDraftCostTest {

    @Autowired
    private BookingService bookingService;

    @Autowired
    private ResourceRepository resourceRepository;

    @Test
    void draftBookingCalculatesCreditCost() {
        // Retrieve a seeded test resource (e.g., "Small Lab")
        Resource resource = resourceRepository.findAll()
                .stream()
                .filter(r -> "Small Lab".equals(r.getName()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Test resource not found"));

        // 2.5‑hour booking (150 minutes)
        LocalDateTime start = LocalDateTime.now().plusMinutes(5);
        LocalDateTime end = start.plusMinutes(150);

        Booking booking = bookingService.createDraft(1L, resource.getId(), start, end);

        assertNotNull(booking.getCreditCost(), "Credit cost should be calculated during draft creation");
        // Expected: ceil(2.5 * baseRate) where baseRate = 5.00 for Small Lab
        BigDecimal expected = new BigDecimal(5 * 2.5).setScale(0, java.math.RoundingMode.CEILING);
        assertEquals(expected, booking.getCreditCost(), "Credit cost should match pricing formula");
    }
}
