package com.mrlbs.booking;

import com.mrlbs.booking.credit.CreditAccount;
import com.mrlbs.booking.credit.CreditAccountRepository;
import com.mrlbs.booking.pricing.BookingPriceCalculator;
import com.mrlbs.resource.LabResource;
import com.mrlbs.resource.ResourceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class BookingServiceTests {
    @Mock BookingRepository bookingRepository;
    @Mock ResourceRepository resourceRepository;
    @Mock CreditAccountRepository creditAccountRepository;
    private BookingService service;
    private LabResource resource;
    private UUID resourceId;

    @BeforeEach
    void setUp() {
        service = new BookingService(bookingRepository, resourceRepository,
                creditAccountRepository, new BookingPriceCalculator());
        resource = mock(LabResource.class);
        resourceId = UUID.randomUUID();
        lenient().when(resource.getId()).thenReturn(resourceId);
        lenient().when(resource.getBaseRate()).thenReturn(new BigDecimal("4"));
        lenient().when(resource.getMinimumDurationHours()).thenReturn(new BigDecimal("1"));
        lenient().when(resource.getMaximumContentionMultiplier()).thenReturn(new BigDecimal("3"));
    }

    @Test
    void rejectsZeroOrReversedTimeWindowsWithReasonCodeBeforePersistence() {
        var start = LocalDateTime.now().plusDays(1);
        var rejected = assertThrows(BookingRejectedException.class,
                () -> service.submit("member@iitk.ac.in", new BookingRequest(resourceId, start, start)));

        assertEquals(BookingRejectionReason.INVALID_TIME_WINDOW, rejected.getReason());
        verify(bookingRepository, never()).save(any());
        verify(creditAccountRepository, never()).findForUpdate(any());
    }

    @Test
    void rejectsUnknownResourceWithStableReasonCode() {
        when(resourceRepository.findById(resourceId)).thenReturn(Optional.empty());
        var start = LocalDateTime.now().plusDays(1);

        var rejected = assertThrows(BookingRejectedException.class,
                () -> service.submit("member@iitk.ac.in", new BookingRequest(resourceId, start, start.plusHours(1))));

        assertEquals(BookingRejectionReason.RESOURCE_NOT_FOUND, rejected.getReason());
        verify(bookingRepository, never()).save(any());
    }

    @Test
    void insufficientCreditsRejectsWithoutSavingBookingOrChangingBalance() {
        when(resourceRepository.findById(resourceId)).thenReturn(Optional.of(resource));
        CreditAccount account = new CreditAccount("member@iitk.ac.in", new BigDecimal("3"));
        when(creditAccountRepository.findForUpdate("member@iitk.ac.in")).thenReturn(Optional.of(account));
        var start = LocalDateTime.now().plusDays(1);

        var rejected = assertThrows(BookingRejectedException.class,
                () -> service.submit("member@iitk.ac.in", new BookingRequest(resourceId, start, start.plusMinutes(10))));

        assertEquals(BookingRejectionReason.INSUFFICIENT_CREDITS, rejected.getReason());
        assertEquals(new BigDecimal("3"), account.getBalance());
        verify(bookingRepository, never()).save(any());
        verify(creditAccountRepository, never()).save(any());
    }

    @Test
    void successfulSubmissionPersistsConfirmedBookingAndDebitsTheCalculatedCost() {
        when(resourceRepository.findById(resourceId)).thenReturn(Optional.of(resource));
        CreditAccount account = new CreditAccount("member@iitk.ac.in", new BigDecimal("10"));
        when(creditAccountRepository.findForUpdate("member@iitk.ac.in")).thenReturn(Optional.of(account));
        when(bookingRepository.save(any(Booking.class))).thenAnswer(invocation -> invocation.getArgument(0));
        var start = LocalDateTime.now().plusDays(1);

        Booking result = service.submit("member@iitk.ac.in",
                new BookingRequest(resourceId, start, start.plusMinutes(10)));

        assertEquals(BookingStatus.CONFIRMED, result.getStatus());
        assertEquals(new BigDecimal("4"), result.getCreditCost());
        assertEquals(new BigDecimal("6"), account.getBalance());
        ArgumentCaptor<Booking> captor = ArgumentCaptor.forClass(Booking.class);
        verify(bookingRepository).save(captor.capture());
        assertEquals("member@iitk.ac.in", captor.getValue().getOwnerEmail());
        verify(creditAccountRepository).save(account);
    }

    @Test
    void missingCreditAccountIsRejectedAsInsufficientCredits() {
        when(resourceRepository.findById(resourceId)).thenReturn(Optional.of(resource));
        when(creditAccountRepository.findForUpdate("new@iitk.ac.in")).thenReturn(Optional.empty());
        var start = LocalDateTime.now().plusDays(1);

        var rejected = assertThrows(BookingRejectedException.class,
                () -> service.submit("new@iitk.ac.in", new BookingRequest(resourceId, start, start.plusHours(1))));

        assertEquals(BookingRejectionReason.INSUFFICIENT_CREDITS, rejected.getReason());
        verify(bookingRepository, never()).save(any());
    }
}
