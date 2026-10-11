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
        when(resourceRepository.findByIdForUpdate(resourceId)).thenReturn(Optional.empty());
        var start = LocalDateTime.now().plusDays(1);

        var rejected = assertThrows(BookingRejectedException.class,
                () -> service.submit("member@iitk.ac.in", new BookingRequest(resourceId, start, start.plusHours(1))));

        assertEquals(BookingRejectionReason.RESOURCE_NOT_FOUND, rejected.getReason());
        verify(bookingRepository, never()).save(any());
    }

    @Test
    void insufficientCreditsRejectsWithoutSavingBookingOrChangingBalance() {
        when(resourceRepository.findByIdForUpdate(resourceId)).thenReturn(Optional.of(resource));
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
        when(resourceRepository.findByIdForUpdate(resourceId)).thenReturn(Optional.of(resource));
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
        verify(resourceRepository).findByIdForUpdate(resourceId);
    }

    @Test
    void missingCreditAccountIsRejectedAsInsufficientCredits() {
        when(resourceRepository.findByIdForUpdate(resourceId)).thenReturn(Optional.of(resource));
        when(creditAccountRepository.findForUpdate("new@iitk.ac.in")).thenReturn(Optional.empty());
        var start = LocalDateTime.now().plusDays(1);

        var rejected = assertThrows(BookingRejectedException.class,
                () -> service.submit("new@iitk.ac.in", new BookingRequest(resourceId, start, start.plusHours(1))));

        assertEquals(BookingRejectionReason.INSUFFICIENT_CREDITS, rejected.getReason());
        verify(bookingRepository, never()).save(any());
    }

    @Test
    void approvalRequiredResourceCreatesPendingBookingWithoutDebitingCredits() {
        when(resourceRepository.findByIdForUpdate(resourceId)).thenReturn(Optional.of(resource));
        when(resource.isApprovalRequired()).thenReturn(true);
        CreditAccount account = new CreditAccount("member@iitk.ac.in", new BigDecimal("10"));
        when(creditAccountRepository.findForUpdate("member@iitk.ac.in")).thenReturn(Optional.of(account));
        when(bookingRepository.save(any(Booking.class))).thenAnswer(invocation -> invocation.getArgument(0));
        var start = LocalDateTime.now().plusDays(1);

        Booking result = service.submit("member@iitk.ac.in",
                new BookingRequest(resourceId, start, start.plusHours(1)));

        assertEquals(BookingStatus.PENDING_APPROVAL, result.getStatus());
        assertEquals(new BigDecimal("10"), account.getBalance());
        verify(creditAccountRepository, never()).save(any());
    }

    @Test
    void pendingApprovalQueueReadsOnlyPendingBookingsInStartOrder() {
        when(bookingRepository.findByStatusOrderByStartTimeAsc(BookingStatus.PENDING_APPROVAL))
                .thenReturn(java.util.List.of());

        assertEquals(java.util.List.of(), service.pendingApprovals());
        verify(bookingRepository).findByStatusOrderByStartTimeAsc(BookingStatus.PENDING_APPROVAL);
    }

    @Test
    void rejectsConfirmedOverlapAfterAcquiringResourceLockAndBeforeWriting() {
        when(resourceRepository.findByIdForUpdate(resourceId)).thenReturn(Optional.of(resource));
        when(bookingRepository.findFirstByResourceIdAndStatusAndStartTimeLessThanAndEndTimeGreaterThan(
                any(), org.mockito.ArgumentMatchers.eq(BookingStatus.CONFIRMED), any(), any()))
                .thenReturn(Optional.of(mock(Booking.class)));
        var start = LocalDateTime.now().plusDays(1).withHour(10).withMinute(0);

        var rejected = assertThrows(BookingRejectedException.class,
                () -> service.submit("member@iitk.ac.in", new BookingRequest(resourceId, start, start.plusHours(1))));

        assertEquals(BookingRejectionReason.SLOT_NO_LONGER_AVAILABLE, rejected.getReason());
        verify(resourceRepository).findByIdForUpdate(resourceId);
        verify(bookingRepository, never()).save(any());
        verify(creditAccountRepository, never()).findForUpdate(any());
    }

    @Test
    void successfulModificationSecuresNewResourceSlotThenUpdatesWindowAndPrice() {
        UUID bookingId = UUID.randomUUID();
        Booking booking = modifiableBooking(bookingId);
        when(bookingRepository.findOwnedBookingForUpdate(bookingId, "member@iitk.ac.in"))
                .thenReturn(Optional.of(booking));
        when(resourceRepository.findByIdForUpdate(resourceId)).thenReturn(Optional.of(resource));
        LocalDateTime start = newTime();
        when(bookingRepository.findFirstByResourceIdAndStatusAndStartTimeLessThanAndEndTimeGreaterThanAndIdNot(
                resourceId, BookingStatus.CONFIRMED, start.plusHours(2), start, bookingId))
                .thenReturn(Optional.empty());
        CreditAccount account = new CreditAccount("member@iitk.ac.in", new BigDecimal("10"));
        when(creditAccountRepository.findForUpdate("member@iitk.ac.in")).thenReturn(Optional.of(account));
        when(bookingRepository.save(booking)).thenReturn(booking);
        Booking updated = service.modifyTimeWindow("member@iitk.ac.in", bookingId,
                new BookingTimeWindowRequest(start, start.plusHours(2)));

        assertEquals(booking, updated);
        verify(booking).setStartTime(start);
        verify(booking).setEndTime(start.plusHours(2));
        verify(booking).setCreditCost(new BigDecimal("8"));
        assertEquals(new BigDecimal("6"), account.getBalance());
        verify(resourceRepository).findByIdForUpdate(resourceId);
        verify(creditAccountRepository).save(account);
        verify(bookingRepository).save(booking);
    }

    @Test
    void unavailableReplacementSlotLeavesOriginalBookingAndCreditsUntouched() {
        UUID bookingId = UUID.randomUUID();
        Booking booking = modifiableBooking(bookingId);
        when(bookingRepository.findOwnedBookingForUpdate(bookingId, "member@iitk.ac.in"))
                .thenReturn(Optional.of(booking));
        when(resourceRepository.findByIdForUpdate(resourceId)).thenReturn(Optional.of(resource));
        LocalDateTime start = newTime();
        when(bookingRepository.findFirstByResourceIdAndStatusAndStartTimeLessThanAndEndTimeGreaterThanAndIdNot(
                resourceId, BookingStatus.CONFIRMED, start.plusHours(1), start, bookingId))
                .thenReturn(Optional.of(mock(Booking.class)));

        var rejected = assertThrows(BookingRejectedException.class, () -> service.modifyTimeWindow(
                "member@iitk.ac.in", bookingId, new BookingTimeWindowRequest(start, start.plusHours(1))));

        assertEquals(BookingRejectionReason.SLOT_NO_LONGER_AVAILABLE, rejected.getReason());
        verify(booking, never()).setStartTime(any());
        verify(booking, never()).setEndTime(any());
        verify(bookingRepository, never()).save(any());
        verify(creditAccountRepository, never()).findForUpdate(any());
    }

    @Test
    void modificationAfterOriginalStartIsRejectedBeforeAcquiringReplacementSlot() {
        UUID bookingId = UUID.randomUUID();
        Booking booking = mock(Booking.class);
        when(booking.getStatus()).thenReturn(BookingStatus.CONFIRMED);
        when(booking.getStartTime()).thenReturn(LocalDateTime.now().minusMinutes(1));
        when(bookingRepository.findOwnedBookingForUpdate(bookingId, "member@iitk.ac.in"))
                .thenReturn(Optional.of(booking));

        var rejected = assertThrows(BookingRejectedException.class, () -> service.modifyTimeWindow(
                "member@iitk.ac.in", bookingId, new BookingTimeWindowRequest(newTime(), newTime().plusHours(1))));

        assertEquals(BookingRejectionReason.BOOKING_NOT_MODIFIABLE, rejected.getReason());
        verify(resourceRepository, never()).findByIdForUpdate(any());
        verify(bookingRepository, never()).save(any());
    }

    @Test
    void insufficientCreditsForMoreExpensiveWindowPreservesOriginalBooking() {
        UUID bookingId = UUID.randomUUID();
        Booking booking = modifiableBooking(bookingId);
        when(bookingRepository.findOwnedBookingForUpdate(bookingId, "member@iitk.ac.in"))
                .thenReturn(Optional.of(booking));
        when(resourceRepository.findByIdForUpdate(resourceId)).thenReturn(Optional.of(resource));
        LocalDateTime start = newTime();
        when(bookingRepository.findFirstByResourceIdAndStatusAndStartTimeLessThanAndEndTimeGreaterThanAndIdNot(
                resourceId, BookingStatus.CONFIRMED, start.plusHours(2), start, bookingId))
                .thenReturn(Optional.empty());
        CreditAccount account = new CreditAccount("member@iitk.ac.in", new BigDecimal("3"));
        when(creditAccountRepository.findForUpdate("member@iitk.ac.in")).thenReturn(Optional.of(account));

        var rejected = assertThrows(BookingRejectedException.class, () -> service.modifyTimeWindow(
                "member@iitk.ac.in", bookingId, new BookingTimeWindowRequest(start, start.plusHours(2))));

        assertEquals(BookingRejectionReason.INSUFFICIENT_CREDITS, rejected.getReason());
        assertEquals(new BigDecimal("3"), account.getBalance());
        verify(booking, never()).setStartTime(any());
        verify(booking, never()).setEndTime(any());
        verify(bookingRepository, never()).save(any());
        verify(creditAccountRepository, never()).save(any());
    }

    private Booking modifiableBooking(UUID id) {
        Booking booking = mock(Booking.class);
        lenient().when(booking.getId()).thenReturn(id);
        lenient().when(booking.getOwnerEmail()).thenReturn("member@iitk.ac.in");
        lenient().when(booking.getResource()).thenReturn(resource);
        lenient().when(booking.getStartTime()).thenReturn(LocalDateTime.now().plusDays(3));
        lenient().when(booking.getEndTime()).thenReturn(LocalDateTime.now().plusDays(3).plusHours(1));
        lenient().when(booking.getStatus()).thenReturn(BookingStatus.CONFIRMED);
        lenient().when(booking.getCreditCost()).thenReturn(new BigDecimal("4"));
        return booking;
    }

    private LocalDateTime newTime() {
        return LocalDateTime.now().plusDays(5).withHour(10).withMinute(0).withSecond(0).withNano(0);
    }
}
