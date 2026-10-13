package com.mrlbs.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mrlbs.booking.Booking;
import com.mrlbs.booking.BookingRepository;
import com.mrlbs.booking.BookingStatus;
import com.mrlbs.resource.BookingPolicy;
import com.mrlbs.resource.BookingRule;
import com.mrlbs.resource.LabResource;
import com.mrlbs.resource.LabResourceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import org.mockito.quality.Strictness;
import org.mockito.junit.jupiter.MockitoSettings;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AiRecommendationServiceTests {

    @Mock
    private GeminiClient geminiClient;

    @Mock
    private LabResourceRepository resourceRepository;

    @Mock
    private BookingRepository bookingRepository;

    private AiRecommendationService service;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        service = new AiRecommendationService(geminiClient, resourceRepository, bookingRepository, objectMapper);
    }

    @Test
    void parseIntent_requiresDate_asksFollowUpWhenMissing() {
        when(geminiClient.isConfigured()).thenReturn(false);

        ExtractedIntent intent = service.parseIntent("I need a lecture hall for 80 people from 10 AM to noon", LocalDate.of(2026, 10, 14));

        assertFalse(intent.hasDate(), "Intent should not have a date if user omitted it");
        assertNotNull(intent.clarifyingQuestion());
        assertTrue(intent.clarifyingQuestion().contains("date"));
    }

    @Test
    void parseIntent_withRelativeDate_parsesSuccessfully() {
        LocalDate today = LocalDate.of(2026, 10, 14);
        when(geminiClient.isConfigured()).thenReturn(false);

        ExtractedIntent intent = service.parseIntent("I need a lecture hall for 80 people tomorrow from 10 AM to noon", today);

        assertTrue(intent.hasDate());
        assertEquals(LocalDate.of(2026, 10, 15), intent.date());
        assertEquals(80, intent.minCapacity());
        assertEquals("Lecture Hall", intent.resourceType());
        assertEquals(LocalTime.of(10, 0), intent.startTime());
        assertEquals(LocalTime.of(12, 0), intent.endTime());
    }

    @Test
    void getRecommendations_exactMatch_rankedTop() {
        LocalDate today = LocalDate.of(2026, 10, 14);
        when(geminiClient.isConfigured()).thenReturn(false);

        UUID l1Id = UUID.randomUUID();
        LabResource l1 = createResource(l1Id, "L1", "Lecture Hall", "Complex", 100, BookingPolicy.FCFS);

        UUID l2Id = UUID.randomUUID();
        LabResource l2 = createResource(l2Id, "L2", "Lecture Hall", "Complex", 50, BookingPolicy.FCFS);

        when(resourceRepository.findByActiveTrue()).thenReturn(List.of(l1, l2));
        when(bookingRepository.findBookedResourceIdsInSlot(any(), any())).thenReturn(List.of());

        AiPromptRequest request = new AiPromptRequest("I need a lecture hall for 80 people tomorrow from 10:00 to 12:00", today.toString());
        AiRecommendationResponse response = service.getRecommendations(request);

        assertTrue(response.success());
        assertNotNull(response.topRecommendation());
        assertEquals("L1", response.topRecommendation().name());
        assertEquals("EXACT", response.topRecommendation().matchType());
        assertTrue(response.topRecommendation().available());

        assertFalse(response.alternatives().isEmpty());
        ResourceRecommendation alt = response.alternatives().getFirst();
        assertEquals("L2", alt.name());
        assertEquals("ALTERNATIVE", alt.matchType());
        assertTrue(alt.explanation().contains("50 seats"));
    }

    @Test
    void getRecommendations_todayWithoutTime_usesUpcomingSlotAndDynamicChips() {
        LocalDate today = LocalDate.now();
        when(geminiClient.isConfigured()).thenReturn(false);

        UUID l1Id = UUID.randomUUID();
        LabResource l1 = createResource(l1Id, "L1", "Lecture Hall", "Complex", 100, BookingPolicy.FCFS);

        when(resourceRepository.findByActiveTrue()).thenReturn(List.of(l1));
        when(bookingRepository.findBookedResourceIdsInSlot(any(), any())).thenReturn(List.of());

        AiPromptRequest request = new AiPromptRequest("Today Lecture Hall", today.toString());
        AiRecommendationResponse response = service.getRecommendations(request);

        assertTrue(response.success());
        assertNotNull(response.topRecommendation());
        assertEquals("L1", response.topRecommendation().name());
        assertNotNull(response.suggestedRefinements());
        assertFalse(response.suggestedRefinements().isEmpty());
    }

    @Test
    void getRecommendations_slotConflict_recommendsAlternativeTime() {
        LocalDate today = LocalDate.of(2026, 10, 14);
        when(geminiClient.isConfigured()).thenReturn(false);

        UUID l1Id = UUID.randomUUID();
        LabResource l1 = createResource(l1Id, "L1", "Lecture Hall", "Complex", 100, BookingPolicy.FCFS);

        when(resourceRepository.findByActiveTrue()).thenReturn(List.of(l1));
        when(bookingRepository.findBookedResourceIdsInSlot(any(), any())).thenReturn(List.of(l1Id));

        AiPromptRequest request = new AiPromptRequest("I need a lecture hall for 80 people tomorrow from 10:00 to 12:00", today.toString());
        AiRecommendationResponse response = service.getRecommendations(request);

        assertTrue(response.success());
        assertNotNull(response.topRecommendation());
        assertEquals("L1", response.topRecommendation().name());
        assertEquals("ALTERNATIVE", response.topRecommendation().matchType());
        assertFalse(response.topRecommendation().available());
        assertEquals(LocalTime.of(12, 0), response.topRecommendation().suggestedStartTime());
        assertTrue(response.topRecommendation().explanation().contains("Time Shift"));
    }

    private LabResource createResource(UUID id, String name, String type, String loc, Integer cap, BookingPolicy policy) {
        LabResource res = new LabResource(
                name, type, loc, cap, List.of("Rules"),
                new BigDecimal("1.00"), new BigDecimal("3.00"), BigDecimal.ZERO
        );
        res.setId(id);
        BookingRule rule = new BookingRule();
        rule.setBookingPolicy(policy);
        rule.setMinDurationMinutes(60);
        rule.setMaxDurationMinutes(240);
        rule.setBufferTimeMinutes(15);
        rule.setMaxBookingsPerMember(3);
        res.setBookingRule(rule);
        return res;
    }
}
