package com.mrlbs.telemetry.analyzer;

import com.mrlbs.booking.Booking;
import com.mrlbs.booking.BookingRepository;
import com.mrlbs.booking.BookingStatus;
import com.mrlbs.resource.LabResource;
import com.mrlbs.telemetry.ResourceTelemetryMapping;
import com.mrlbs.telemetry.ResourceTelemetryMappingRepository;
import com.mrlbs.telemetry.client.ExternalHistoricalTelemetry;
import com.mrlbs.telemetry.client.ExternalResourceHealth;
import com.mrlbs.telemetry.client.ExternalTelemetryMeasurement;
import com.mrlbs.telemetry.client.ResourceTelemetryClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RequesterHistoryAnalyzerTest {

    @Mock
    private BookingRepository bookingRepository;

    @Mock
    private ResourceTelemetryMappingRepository mappingRepository;

    @Mock
    private ResourceTelemetryClient telemetryClient;

    private RequesterHistoryAnalyzer analyzer;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        analyzer = new RequesterHistoryAnalyzer(bookingRepository, mappingRepository, telemetryClient);
    }

    private Booking createBooking(UUID id, UUID memberId, LabResource resource,
                                  LocalDateTime start, LocalDateTime end, BookingStatus status) {
        Booking b = mock(Booking.class);
        when(b.getId()).thenReturn(id);
        when(b.getMemberId()).thenReturn(memberId);
        when(b.getResource()).thenReturn(resource);
        when(b.getStartTime()).thenReturn(start);
        when(b.getEndTime()).thenReturn(end);
        when(b.getStatus()).thenReturn(status);
        return b;
    }

    private LabResource createResource(UUID id, String name, String type) {
        LabResource r = mock(LabResource.class);
        when(r.getId()).thenReturn(id);
        when(r.getName()).thenReturn(name);
        when(r.getType()).thenReturn(type);
        return r;
    }

    @Test
    void testAnalyze_NoPriorBookings() {
        UUID memberId = UUID.randomUUID();
        UUID targetResourceId = UUID.randomUUID();
        UUID pendingBookingId = UUID.randomUUID();

        when(bookingRepository.findByMemberIdOrderByStartTimeDesc(memberId))
                .thenReturn(Collections.emptyList());
        when(mappingRepository.findByResourceId(targetResourceId))
                .thenReturn(Optional.of(new ResourceTelemetryMapping(targetResourceId, "gpu-node-01")));
        when(telemetryClient.getCurrentHealth("gpu-node-01"))
                .thenReturn(Optional.of(new ExternalResourceHealth("gpu-node-01", "HEALTHY", 42.5, 65.0, 68.0, 240.0, Instant.now(), "DEMO", true)));

        RequesterHistoryProfile profile =
                analyzer.analyzeRequesterHistory(memberId, targetResourceId, pendingBookingId);

        assertEquals(0, profile.priorBookingsTotal());
        assertEquals(0, profile.priorBookingsCompleted());
        assertEquals(0, profile.priorBookingsCancelled());
        assertFalse(profile.hasHistoricalTelemetry());
        assertEquals("NO_PRIOR_USAGE", profile.historicalTelemetryQuality());
        assertNull(profile.historicalAvgGpuUtilPct());
        assertNull(profile.historicalPeakGpuUtilPct());
        assertNotNull(profile.currentHealth());
        assertEquals("HEALTHY", profile.currentHealth().healthStatus());
    }

    @Test
    void testAnalyze_WithCompletedBookingsAndHistoricalTelemetry() {
        UUID memberId = UUID.randomUUID();
        UUID targetResourceId = UUID.randomUUID();
        UUID pendingBookingId = UUID.randomUUID();

        LabResource gpuResource = createResource(targetResourceId, "GPU Node 1", "GPU");

        Booking pendingBooking = createBooking(pendingBookingId, memberId, gpuResource,
                LocalDateTime.of(2026, 10, 15, 10, 0), LocalDateTime.of(2026, 10, 15, 12, 0), BookingStatus.PENDING_APPROVAL);

        Booking pastCompleted = createBooking(UUID.randomUUID(), memberId, gpuResource,
                LocalDateTime.of(2026, 10, 10, 10, 0), LocalDateTime.of(2026, 10, 10, 12, 0), BookingStatus.COMPLETED);

        Booking pastCancelled = createBooking(UUID.randomUUID(), memberId, gpuResource,
                LocalDateTime.of(2026, 10, 8, 14, 0), LocalDateTime.of(2026, 10, 8, 16, 0), BookingStatus.CANCELLED);

        when(bookingRepository.findByMemberIdOrderByStartTimeDesc(memberId))
                .thenReturn(List.of(pendingBooking, pastCompleted, pastCancelled));

        when(mappingRepository.findByResourceId(targetResourceId))
                .thenReturn(Optional.of(new ResourceTelemetryMapping(targetResourceId, "gpu-node-01")));

        when(telemetryClient.getCurrentHealth("gpu-node-01"))
                .thenReturn(Optional.of(new ExternalResourceHealth("gpu-node-01", "HEALTHY", 40.0, 60.0, 67.0, 230.0, Instant.now(), "DEMO", true)));

        List<ExternalTelemetryMeasurement> measurements = List.of(
                new ExternalTelemetryMeasurement(Instant.now(), 80.0, 70.0, 68.0, 250.0),
                new ExternalTelemetryMeasurement(Instant.now(), 90.0, 75.0, 72.0, 270.0)
        );
        ExternalHistoricalTelemetry hist = new ExternalHistoricalTelemetry(
                "gpu-node-01", Instant.now(), Instant.now(), "COMPLETE", 100.0, true, "DEMO", measurements);

        when(telemetryClient.getHistoricalTelemetry(eq("gpu-node-01"), any(), any()))
                .thenReturn(Optional.of(hist));

        RequesterHistoryProfile profile =
                analyzer.analyzeRequesterHistory(memberId, targetResourceId, pendingBookingId);

        // Current pending booking must be excluded from prior totals
        assertEquals(2, profile.priorBookingsTotal());
        assertEquals(1, profile.priorBookingsCompleted());
        assertEquals(1, profile.priorBookingsCancelled());

        // Historical telemetry calculated strictly from completed booking
        assertTrue(profile.hasHistoricalTelemetry());
        assertEquals(85.0, profile.historicalAvgGpuUtilPct());
        assertEquals(90.0, profile.historicalPeakGpuUtilPct());
        assertEquals("COMPLETE", profile.historicalTelemetryQuality());

        // Telemetry client should only be queried for the 1 completed booking, NOT the cancelled booking
        verify(telemetryClient, times(1)).getHistoricalTelemetry(eq("gpu-node-01"), any(), any());
    }

    @Test
    void testAnalyze_UnmappedResource() {
        UUID memberId = UUID.randomUUID();
        UUID lectureHallId = UUID.randomUUID();
        UUID pendingBookingId = UUID.randomUUID();

        when(bookingRepository.findByMemberIdOrderByStartTimeDesc(memberId))
                .thenReturn(Collections.emptyList());
        when(mappingRepository.findByResourceId(lectureHallId))
                .thenReturn(Optional.empty());

        RequesterHistoryProfile profile =
                analyzer.analyzeRequesterHistory(memberId, lectureHallId, pendingBookingId);

        assertNull(profile.externalResourceId());
        assertNull(profile.currentHealth());
        assertFalse(profile.hasHistoricalTelemetry());
        assertEquals("NO_PRIOR_USAGE", profile.historicalTelemetryQuality());
        verifyNoInteractions(telemetryClient);
    }
}
