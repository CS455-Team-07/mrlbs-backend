package com.mrlbs.resource;

import com.mrlbs.booking.Booking;
import com.mrlbs.booking.BookingRepository;
import com.mrlbs.booking.BookingStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ResourceSearchServiceTests {
    @Mock LabResourceRepository resourceRepository;
    @Mock BookingRepository bookingRepository;
    private ResourceSearchService service;

    @BeforeEach
    void setUp() { service = new ResourceSearchService(resourceRepository, bookingRepository); }

    @Test
    void searchesByTypeAndCapacityAndShowsDateAvailability() {
        UUID id = UUID.randomUUID();
        LabResource resource = resource(id, "Confocal Microscope", "Microscope", 3);
        LocalDate date = LocalDate.of(2030, 5, 1);
        when(resourceRepository.findAll(any(org.springframework.data.jpa.domain.Specification.class)))
                .thenReturn(List.of(resource));
        when(bookingRepository.findConfirmedBookingsInWindow(any(), any()))
                .thenReturn(List.of());

        var results = service.search("micro", date, 2);

        assertEquals(1, results.size());
        assertEquals("Confocal Microscope", results.getFirst().name());
        assertTrue(results.getFirst().available());
    }

    @Test
    void showsOpenWhenResourceHasPartialBookingOnDate() {
        UUID id = UUID.randomUUID();
        LabResource resource = resource(id, "Lecture Hall", "Lecture Hall", 54);
        LocalDate date = LocalDate.of(2026, 10, 17);
        Booking partialBooking = new Booking("user@iitk.ac.in", resource,
                date.atTime(2, 0), date.atTime(3, 0), BookingStatus.CONFIRMED, BigDecimal.ZERO);

        when(resourceRepository.findAll(any(org.springframework.data.jpa.domain.Specification.class)))
                .thenReturn(List.of(resource));
        when(bookingRepository.findConfirmedBookingsInWindow(any(), any()))
                .thenReturn(List.of(partialBooking));

        var results = service.search("Lecture Hall", date, null);

        assertEquals(1, results.size());
        assertTrue(results.getFirst().available(), "Resource should be OPEN if at least 1 slot is available");
    }

    @Test
    void showsBookedWhenEntireDayIsBooked() {
        UUID id = UUID.randomUUID();
        LabResource resource = resource(id, "Lecture Hall", "Lecture Hall", 54);
        LocalDate date = LocalDate.of(2026, 10, 17);
        Booking fullDay = new Booking("user@iitk.ac.in", resource,
                date.atStartOfDay(), date.plusDays(1).atStartOfDay(), BookingStatus.CONFIRMED, BigDecimal.ZERO);

        when(resourceRepository.findAll(any(org.springframework.data.jpa.domain.Specification.class)))
                .thenReturn(List.of(resource));
        when(bookingRepository.findConfirmedBookingsInWindow(any(), any()))
                .thenReturn(List.of(fullDay));

        var results = service.search("Lecture Hall", date, null);

        assertEquals(1, results.size());
        assertFalse(results.getFirst().available(), "Resource should be BOOKED if 0 slots available");
    }

    @Test
    void reportsBookedIntervalsAndOpenGapsForSelectedDate() {
        UUID id = UUID.randomUUID();
        LocalDate date = LocalDate.of(2030, 5, 1);
        LabResource resource = resource(id, "Microscope", "Microscope", 3);
        Booking booking = new Booking("hidden@iitk.ac.in", resource,
                date.atTime(10, 0), date.atTime(11, 30), BookingStatus.CONFIRMED, BigDecimal.TEN);
        when(resourceRepository.findById(id)).thenReturn(Optional.of(resource));
        when(bookingRepository.findByResourceIdAndStatusAndStartTimeLessThanAndEndTimeGreaterThanOrderByStartTimeAsc(
                id, BookingStatus.CONFIRMED, date.plusDays(1).atStartOfDay(), date.atStartOfDay()))
                .thenReturn(List.of(booking));

        var response = service.availability(id, date);

        assertEquals(1, response.bookedSlots().size());
        assertEquals(date.atTime(10, 0), response.bookedSlots().getFirst().startTime());
        assertEquals(2, response.availableSlots().size());
        assertEquals(date.atStartOfDay(), response.availableSlots().getFirst().startTime());
        assertEquals(date.atTime(10, 0), response.availableSlots().getFirst().endTime());
        assertEquals(date.atTime(11, 30), response.availableSlots().getLast().startTime());
        assertEquals(date.plusDays(1).atStartOfDay(), response.availableSlots().getLast().endTime());
        assertFalse(response.toString().contains("hidden@iitk.ac.in"));
    }

    @Test
    void rejectsAvailabilityForUnknownResource() {
        UUID id = UUID.randomUUID();
        when(resourceRepository.findById(id)).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class,
                () -> service.availability(id, LocalDate.of(2030, 5, 1)));
    }

    @Test
    void slotBasedSearchUsesBatchLookup() {
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        LabResource res1 = resource(id1, "Resource 1", "Server", 10);
        LabResource res2 = resource(id2, "Resource 2", "Server", 10);
        LocalDateTime start = LocalDateTime.of(2030, 11, 10, 10, 0);
        LocalDateTime end = LocalDateTime.of(2030, 11, 10, 12, 0);

        when(resourceRepository.findAll(any(org.springframework.data.jpa.domain.Specification.class)))
                .thenReturn(List.of(res1, res2));
        when(bookingRepository.findConfirmedBookedResourceIdsInSlot(eq(start), eq(end)))
                .thenReturn(List.of(id1));

        var results = service.search("Server", start.toLocalDate(), start, end, null);

        assertEquals(2, results.size());
        assertFalse(results.stream().filter(r -> r.id().equals(id1)).findFirst().get().available());
        assertTrue(results.stream().filter(r -> r.id().equals(id2)).findFirst().get().available());
    }

    @Test
    void presentDayAvailabilityStartsFromNextHour() {
        UUID id = UUID.randomUUID();
        LabResource res = resource(id, "Resource 1", "Server", 10);
        LocalDate today = LocalDate.now();
        LocalDateTime nextHour = LocalDateTime.now().truncatedTo(java.time.temporal.ChronoUnit.HOURS).plusHours(1);

        when(resourceRepository.findById(id)).thenReturn(java.util.Optional.of(res));
        when(bookingRepository.findByResourceIdAndStatusAndStartTimeLessThanAndEndTimeGreaterThanOrderByStartTimeAsc(
                eq(id), eq(BookingStatus.CONFIRMED), any(), any()))
                .thenReturn(List.of());

        var availability = service.availability(id, today);

        if (nextHour.isBefore(today.plusDays(1).atStartOfDay())) {
            assertFalse(availability.availableSlots().isEmpty());
            assertEquals(nextHour, availability.availableSlots().get(0).startTime());
        } else {
            // Day has ended
            assertTrue(availability.availableSlots().isEmpty());
        }
    }

    private static LabResource resource(UUID id, String name, String type, int capacity) {
        return new LabResource(name, type, "Building A", capacity, List.of("Safety rules"),
                BigDecimal.ONE, new BigDecimal("3"), BigDecimal.ONE) {
            @Override public UUID getId() { return id; }
        };
    }
}
