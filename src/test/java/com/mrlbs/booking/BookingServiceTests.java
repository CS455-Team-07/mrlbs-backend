package com.mrlbs.booking;

import com.mrlbs.booking.credit.CreditAccount;
import com.mrlbs.booking.credit.CreditAccountRepository;
import com.mrlbs.booking.pricing.BookingPriceCalculator;
import com.mrlbs.resource.LabResource;
import com.mrlbs.resource.LabResourceRepository;
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
    @Mock LabResourceRepository resourceRepository;
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

    @Test
    void createDraftInitializesInDraftWithoutDebitingCredits() {
        when(resourceRepository.findById(resourceId)).thenReturn(Optional.of(resource));
        when(bookingRepository.save(any(Booking.class))).thenAnswer(invocation -> invocation.getArgument(0));
        var start = LocalDateTime.now().plusDays(1);

        Booking draft = service.createDraft("member@iitk.ac.in",
                new BookingRequest(resourceId, start, start.plusHours(2)));

        assertEquals(BookingStatus.DRAFT, draft.getStatus());
        assertEquals(new BigDecimal("8"), draft.getCreditCost());
        verify(creditAccountRepository, never()).findForUpdate(any());
        verify(creditAccountRepository, never()).save(any());
    }

    @Test
    void rejectsDurationShorterThanMinimumRule() {
        var rule = new com.mrlbs.resource.BookingRule();
        rule.setMinDurationMinutes(60);
        rule.setMaxDurationMinutes(180);
        when(resource.getBookingRule()).thenReturn(rule);
        when(resourceRepository.findById(resourceId)).thenReturn(Optional.of(resource));
        var start = LocalDateTime.now().plusDays(1);

        var rejected = assertThrows(BookingRejectedException.class,
                () -> service.createDraft("member@iitk.ac.in", new BookingRequest(resourceId, start, start.plusMinutes(30))));

        assertEquals(BookingRejectionReason.DURATION_TOO_SHORT, rejected.getReason());
    }

    @Test
    void rejectsDurationLongerThanMaximumRule() {
        var rule = new com.mrlbs.resource.BookingRule();
        rule.setMinDurationMinutes(30);
        rule.setMaxDurationMinutes(120);
        when(resource.getBookingRule()).thenReturn(rule);
        when(resourceRepository.findById(resourceId)).thenReturn(Optional.of(resource));
        var start = LocalDateTime.now().plusDays(1);

        var rejected = assertThrows(BookingRejectedException.class,
                () -> service.createDraft("member@iitk.ac.in", new BookingRequest(resourceId, start, start.plusHours(3))));

        assertEquals(BookingRejectionReason.DURATION_TOO_LONG, rejected.getReason());
    }

    @Test
    void rejectsBookingWhenMemberQuotaExceeded() {
        var rule = new com.mrlbs.resource.BookingRule();
        rule.setMinDurationMinutes(30);
        rule.setMaxDurationMinutes(180);
        rule.setMaxBookingsPerMember(2);
        when(resource.getBookingRule()).thenReturn(rule);
        when(resourceRepository.findById(resourceId)).thenReturn(Optional.of(resource));
        when(bookingRepository.countActiveBookingsForMember(any(), any(), any())).thenReturn(2L);
        var start = LocalDateTime.now().plusDays(1);

        var rejected = assertThrows(BookingRejectedException.class,
                () -> service.createDraft("member@iitk.ac.in", new BookingRequest(resourceId, start, start.plusHours(1))));

        assertEquals(BookingRejectionReason.QUOTA_EXCEEDED, rejected.getReason());
    }

    @Test
    void exactSameSlotStillCreatesInProgressWaitlistBooking() {
        when(resourceRepository.findById(resourceId)).thenReturn(Optional.of(resource));
        var start = LocalDateTime.now().plusDays(1);
        var existingBooking = new Booking("other@iitk.ac.in", resource, start, start.plusHours(1),
                BookingStatus.CONFIRMED, new BigDecimal("4"));
        when(bookingRepository.findOverlappingConfirmedForUpdate(any(), any(), any()))
                .thenReturn(java.util.List.of(existingBooking));
        when(bookingRepository.save(any(Booking.class))).thenAnswer(i -> i.getArgument(0));

        CreditAccount account = new CreditAccount("member@iitk.ac.in", new BigDecimal("100"));
        when(creditAccountRepository.findForUpdate("member@iitk.ac.in")).thenReturn(Optional.of(account));

        Booking booking = service.submit("member@iitk.ac.in", new BookingRequest(resourceId, start, start.plusHours(1)));

        assertEquals(BookingStatus.IN_PROGRESS, booking.getStatus());
    }

    @Test
    void approvalRequiredResourcesUsePendingApprovalStatus() {
        var rule = new com.mrlbs.resource.BookingRule();
        rule.setBookingPolicy(com.mrlbs.resource.BookingPolicy.APPROVAL_REQUIRED);
        when(resource.getBookingRule()).thenReturn(rule);
        when(resourceRepository.findById(resourceId)).thenReturn(Optional.of(resource));
        when(bookingRepository.findOverlappingConfirmedForUpdate(any(), any(), any())).thenReturn(java.util.List.of());
        when(bookingRepository.save(any(Booking.class))).thenAnswer(i -> i.getArgument(0));

        CreditAccount account = new CreditAccount("member@iitk.ac.in", new BigDecimal("100"));
        when(creditAccountRepository.findForUpdate("member@iitk.ac.in")).thenReturn(Optional.of(account));

        Booking booking = service.submit("member@iitk.ac.in",
                new BookingRequest(resourceId, LocalDateTime.now().plusDays(1), LocalDateTime.now().plusDays(1).plusHours(1)));

        assertEquals(BookingStatus.PENDING_APPROVAL, booking.getStatus());
    }

    @Test
    void partialOverlapWithBookedSlotIsRejectedAsSlotConflict() {
        when(resourceRepository.findById(resourceId)).thenReturn(Optional.of(resource));
        var start = LocalDateTime.now().plusDays(1);
        var existingBooking = new Booking("other@iitk.ac.in", resource, start, start.plusHours(2),
                BookingStatus.CONFIRMED, new BigDecimal("8"));
        when(bookingRepository.findOverlappingConfirmedForUpdate(any(), any(), any()))
                .thenReturn(java.util.List.of(existingBooking));

        var rejected = assertThrows(BookingRejectedException.class,
                () -> service.submit("member@iitk.ac.in", new BookingRequest(resourceId, start.plusMinutes(30), start.plusHours(3))));

        assertEquals(BookingRejectionReason.SLOT_CONFLICT, rejected.getReason());
        verify(bookingRepository, never()).save(any());
    }

    @Test
    void bufferViolationIsRejectedAsSlotConflict() {
        var rule = new com.mrlbs.resource.BookingRule();
        rule.setBufferTimeMinutes(15);
        when(resource.getBookingRule()).thenReturn(rule);
        when(resourceRepository.findById(resourceId)).thenReturn(Optional.of(resource));
        var start = LocalDateTime.now().plusDays(1);
        when(bookingRepository.findOverlappingConfirmedForUpdate(any(), any(), any()))
                .thenReturn(java.util.List.of());
        var adjacentBooking = mock(Booking.class);
        when(bookingRepository.findConfirmedInWindow(any(), any(), any()))
                .thenReturn(java.util.List.of(adjacentBooking));

        var rejected = assertThrows(BookingRejectedException.class,
                () -> service.submit("member@iitk.ac.in", new BookingRequest(resourceId, start, start.plusHours(1))));

        assertEquals(BookingRejectionReason.SLOT_CONFLICT, rejected.getReason());
        verify(bookingRepository, never()).save(any());
    }

    @Test
    void confirmDraftSuccessfullyAcquiresSlotDebitsCreditsAndSetsStatusConfirmedForSameBookingId() {
        UUID draftId = UUID.randomUUID();
        var start = LocalDateTime.now().plusDays(1);
        Booking draft = new Booking("member@iitk.ac.in", resource, start, start.plusHours(1),
                BookingStatus.DRAFT, new BigDecimal("4"));
        draft.setId(draftId);
        when(bookingRepository.findById(draftId)).thenReturn(Optional.of(draft));
        when(bookingRepository.findOverlappingConfirmedForUpdate(any(), any(), any())).thenReturn(java.util.List.of());
        when(bookingRepository.save(any(Booking.class))).thenAnswer(invocation -> invocation.getArgument(0));

        CreditAccount account = new CreditAccount("member@iitk.ac.in", new BigDecimal("10"));
        when(creditAccountRepository.findForUpdate("member@iitk.ac.in")).thenReturn(Optional.of(account));

        Booking confirmed = service.confirmDraft("member@iitk.ac.in", draftId);

        assertEquals(draftId, confirmed.getId());
        assertEquals(BookingStatus.CONFIRMED, confirmed.getStatus());
        assertEquals(new BigDecimal("4"), confirmed.getCreditCost());
        assertEquals(new BigDecimal("6"), account.getBalance());
        verify(creditAccountRepository).save(account);
    }

    @Test
    void confirmDraftWithExactSameSlotSetsStatusInProgressEvenWithLargeCapacity() {
        UUID draftId = UUID.randomUUID();
        var start = LocalDateTime.now().plusDays(1);
        Booking draft = new Booking("member@iitk.ac.in", resource, start, start.plusHours(1),
                BookingStatus.DRAFT, new BigDecimal("4"));
        draft.setId(draftId);
        when(bookingRepository.findById(draftId)).thenReturn(Optional.of(draft));
        var existingBooking = new Booking("other@iitk.ac.in", resource, start, start.plusHours(1),
                BookingStatus.CONFIRMED, new BigDecimal("4"));
        when(bookingRepository.findOverlappingConfirmedForUpdate(any(), any(), any()))
                .thenReturn(java.util.List.of(existingBooking));
        when(bookingRepository.save(any(Booking.class))).thenAnswer(invocation -> invocation.getArgument(0));

        CreditAccount account = new CreditAccount("member@iitk.ac.in", new BigDecimal("10"));
        when(creditAccountRepository.findForUpdate("member@iitk.ac.in")).thenReturn(Optional.of(account));

        Booking confirmed = service.confirmDraft("member@iitk.ac.in", draftId);

        assertEquals(draftId, confirmed.getId());
        assertEquals(BookingStatus.IN_PROGRESS, confirmed.getStatus());
    }

    @Test
    void confirmDraftWithSubsetOfBookedSlotSetsStatusInProgress() {
        UUID draftId = UUID.randomUUID();
        var start = LocalDateTime.now().plusDays(1);
        Booking draft = new Booking("member@iitk.ac.in", resource, start.plusMinutes(30), start.plusMinutes(90),
                BookingStatus.DRAFT, new BigDecimal("4"));
        draft.setId(draftId);
        when(bookingRepository.findById(draftId)).thenReturn(Optional.of(draft));
        var existingBooking = new Booking("other@iitk.ac.in", resource, start, start.plusHours(2),
                BookingStatus.CONFIRMED, new BigDecimal("8"));
        when(bookingRepository.findOverlappingConfirmedForUpdate(any(), any(), any()))
                .thenReturn(java.util.List.of(existingBooking));
        when(bookingRepository.save(any(Booking.class))).thenAnswer(invocation -> invocation.getArgument(0));

        CreditAccount account = new CreditAccount("member@iitk.ac.in", new BigDecimal("10"));
        when(creditAccountRepository.findForUpdate("member@iitk.ac.in")).thenReturn(Optional.of(account));

        Booking confirmed = service.confirmDraft("member@iitk.ac.in", draftId);

        assertEquals(draftId, confirmed.getId());
        assertEquals(BookingStatus.IN_PROGRESS, confirmed.getStatus());
    }

    @Test
    void confirmDraftDeletesDraftWhenCriteriaFails() {
        UUID draftId = UUID.randomUUID();
        var start = LocalDateTime.now().plusDays(1);
        Booking draft = new Booking("member@iitk.ac.in", resource, start, start.plusHours(1),
                BookingStatus.DRAFT, new BigDecimal("4"));
        draft.setId(draftId);
        when(bookingRepository.findById(draftId)).thenReturn(Optional.of(draft));
        when(bookingRepository.findOverlappingConfirmedForUpdate(any(), any(), any())).thenReturn(java.util.List.of());

        // Insufficient balance
        CreditAccount account = new CreditAccount("member@iitk.ac.in", new BigDecimal("1"));
        when(creditAccountRepository.findForUpdate("member@iitk.ac.in")).thenReturn(Optional.of(account));

        assertThrows(BookingRejectedException.class,
                () -> service.confirmDraft("member@iitk.ac.in", draftId));

        verify(bookingRepository).delete(draft);
    }

    @Test
    void deleteBookingRemovesDraftFromRepository() {
        UUID draftId = UUID.randomUUID();
        var start = LocalDateTime.now().plusDays(1);
        Booking draft = new Booking("member@iitk.ac.in", resource, start, start.plusHours(1),
                BookingStatus.DRAFT, new BigDecimal("4"));
        draft.setId(draftId);
        when(bookingRepository.findById(draftId)).thenReturn(Optional.of(draft));

        service.deleteBooking("member@iitk.ac.in", draftId);

        verify(bookingRepository).delete(draft);
    }
}
