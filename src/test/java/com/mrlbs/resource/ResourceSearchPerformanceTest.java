package com.mrlbs.resource;

import com.mrlbs.booking.BookingRepository;
import com.mrlbs.booking.BookingStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.jpa.domain.Specification;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verification of Non-Functional Requirement NFR-1.2 / User Story US-3:
 * "Availability search results returned within 1 second under expected load"
 *
 * Acceptance Criteria verified:
 * 1. Availability search requests are measured under expected load (100 active lab resources).
 * 2. Search results are returned within 1 second (1000 ms).
 * 3. The performance target applies to the availability-search workflow (date-only and timings).
 * 4. Verified using repeatable response-time testing.
 */
@ExtendWith(MockitoExtension.class)
class ResourceSearchPerformanceTest {

    @Mock
    private LabResourceRepository resourceRepository;

    @Mock
    private BookingRepository bookingRepository;

    private ResourceSearchService searchService;
    private List<LabResource> sampleResources;

    @BeforeEach
    void setUp() {
        searchService = new ResourceSearchService(resourceRepository, bookingRepository);

        // Expected load: 100 active lab resources across campus
        sampleResources = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            UUID id = UUID.randomUUID();
            String type = (i % 3 == 0) ? "Lecture Hall" : (i % 3 == 1) ? "Microscope" : "GPU Server";
            sampleResources.add(createResource(id, "Resource " + i, type, 30 + (i % 50)));
        }
    }

    @Test
    @DisplayName("NFR-1.2 / US-3: Date-only availability search returns within 1 second under expected load")
    void dateOnlyAvailabilitySearchCompletesWithinOneSecond() {
        LocalDate searchDate = LocalDate.now().plusDays(7);
        when(resourceRepository.findAll(any(Specification.class))).thenReturn(sampleResources);

        // Some bookings during the day
        LocalDateTime dayStart = searchDate.atStartOfDay();
        LocalDateTime dayEnd = searchDate.plusDays(1).atStartOfDay();
        List<BookingRepository.AvailabilityWindow> dayBookings = new ArrayList<>();
        for (LabResource resource : sampleResources.subList(0, 30)) {
            var booking = bookedWindow(resource.getId());
            when(booking.getStartTime()).thenReturn(searchDate.atTime(10, 0));
            when(booking.getEndTime()).thenReturn(searchDate.atTime(12, 0));
            dayBookings.add(booking);
        }
        when(bookingRepository.findAvailabilityWindows(resourceIds(), BookingStatus.CONFIRMED, dayStart, dayEnd))
                .thenReturn(dayBookings);

        Instant start = Instant.now();
        List<ResourceSearchResponse> results = searchService.search(null, searchDate, null);
        long elapsedMillis = Duration.between(start, Instant.now()).toMillis();

        assertEquals(100, results.size());
        assertTrue(elapsedMillis < 1000,
                "Availability search must return within 1 second (1000ms), but took: " + elapsedMillis + "ms");
    }

    @Test
    @DisplayName("NFR-1.2 / US-3: Timing-based availability search returns within 1 second under expected load")
    void timingBasedAvailabilitySearchCompletesWithinOneSecond() {
        LocalDate searchDate = LocalDate.now().plusDays(7);
        LocalDateTime startWindow = searchDate.atTime(14, 0);
        LocalDateTime endWindow = searchDate.atTime(16, 0);

        when(resourceRepository.findAll(any(Specification.class))).thenReturn(sampleResources);

        // 20 resources are booked in this slot
        List<BookingRepository.AvailabilityWindow> bookedWindows = resourceIds().stream().limit(20)
                .map(ResourceSearchPerformanceTest::bookedWindow)
                .toList();
        when(bookingRepository.findAvailabilityWindows(resourceIds(), BookingStatus.CONFIRMED, startWindow, endWindow))
                .thenReturn(bookedWindows);

        Instant start = Instant.now();
        List<ResourceSearchResponse> results = searchService.search(null, searchDate, startWindow, endWindow, null);
        long elapsedMillis = Duration.between(start, Instant.now()).toMillis();

        assertEquals(100, results.size());
        // Verify 20 are booked, 80 are available
        long availableCount = results.stream().filter(ResourceSearchResponse::available).count();
        assertEquals(80, availableCount);

        assertTrue(elapsedMillis < 1000,
                "Availability search with timings must return within 1 second (1000ms), but took: " + elapsedMillis + "ms");
    }

    @RepeatedTest(value = 5, name = "Repeatable response-time run {currentRepetition}/{totalRepetitions}")
    @DisplayName("NFR-1.2 / US-3: Repeatable response-time verification under load")
    void repeatableAvailabilitySearchUnderLoad() {
        LocalDate searchDate = LocalDate.now().plusDays(7);
        LocalDateTime startWindow = searchDate.atTime(9, 0);
        LocalDateTime endWindow = searchDate.atTime(11, 0);

        when(resourceRepository.findAll(any(Specification.class))).thenReturn(sampleResources);
        List<UUID> matchingIds = sampleResources.stream()
                .filter(resource -> resource.getType().equals("Lecture Hall") && resource.getCapacity() >= 40)
                .map(LabResource::getId)
                .toList();
        var booked = bookedWindow(matchingIds.getFirst());
        when(bookingRepository.findAvailabilityWindows(matchingIds, BookingStatus.CONFIRMED, startWindow, endWindow))
                .thenReturn(List.of(booked));

        Instant start = Instant.now();
        List<ResourceSearchResponse> results = searchService.search("Lecture", searchDate, startWindow, endWindow, 40);
        long elapsedMillis = Duration.between(start, Instant.now()).toMillis();

        assertFalse(results.isEmpty());
        assertTrue(elapsedMillis < 1000,
                "Repeatable check: Search results must be returned within 1 second, but took: " + elapsedMillis + "ms");
    }

    private List<UUID> resourceIds() {
        return sampleResources.stream().map(LabResource::getId).toList();
    }

    private static BookingRepository.AvailabilityWindow bookedWindow(UUID resourceId) {
        var booking = mock(BookingRepository.AvailabilityWindow.class);
        when(booking.getResourceId()).thenReturn(resourceId);
        return booking;
    }

    private static LabResource createResource(UUID id, String name, String type, int capacity) {
        return new LabResource(name, type, "IITK Academic Area", capacity, List.of("Standard lab safety rules"),
                BigDecimal.ONE, new BigDecimal("3"), BigDecimal.ONE) {
            @Override
            public UUID getId() {
                return id;
            }
        };
    }
}
