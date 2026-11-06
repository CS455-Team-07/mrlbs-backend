package com.mrlbs.booking;

import com.mrlbs.booking.credit.CreditAccount;
import com.mrlbs.booking.credit.CreditAccountRepository;
import com.mrlbs.booking.pricing.BookingPriceCalculator;
import com.mrlbs.resource.LabResource;
import com.mrlbs.resource.LabResourceRepository;
import com.mrlbs.resource.BookingRule;
import com.mrlbs.resource.BookingPolicy;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
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
    @Mock com.mrlbs.notification.InAppNotificationRepository notificationRepository;
    @Mock com.mrlbs.waitlist.WaitlistEntryRepository waitlistRepository;
    private BookingService service;
    private LabResource resource;
    private UUID resourceId;

    @BeforeEach
    void setUp() {
        service = new BookingService(bookingRepository, resourceRepository,
                creditAccountRepository, new BookingPriceCalculator(), null, null, waitlistRepository, null, null, null);
        service.setNotificationRepository(notificationRepository);
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
        lenient().when(creditAccountRepository.findForUpdate("member@iitk.ac.in")).thenReturn(Optional.of(account));

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

        Booking booking = service.submit("member@iitk.ac.in",
                new BookingRequest(resourceId, LocalDateTime.now().plusDays(1), LocalDateTime.now().plusDays(1).plusHours(1)));

        assertEquals(BookingStatus.PENDING_APPROVAL, booking.getStatus());
        assertEquals(BigDecimal.ZERO, booking.getCreditCost());
        verify(creditAccountRepository, never()).findForUpdate(any());
    }

    @Test
    void pendingApprovalQueueOnlyReturnsBookingsMatchingTheAdminScope() {
        LabResource lectureHall = mock(LabResource.class);
        when(lectureHall.getType()).thenReturn("Lecture Hall");

        LabResource gpu = mock(LabResource.class);
        when(gpu.getType()).thenReturn("GPU");

        Booking matching = new Booking("member@iitk.ac.in", lectureHall,
                LocalDateTime.now().plusDays(2), LocalDateTime.now().plusDays(2).plusHours(1),
                BookingStatus.PENDING_APPROVAL, new BigDecimal("5"));
        Booking other = new Booking("member2@iitk.ac.in", gpu,
                LocalDateTime.now().plusDays(3), LocalDateTime.now().plusDays(3).plusHours(1),
                BookingStatus.PENDING_APPROVAL, new BigDecimal("5"));

        when(bookingRepository.findByStatusOrderByStartTimeAsc(BookingStatus.PENDING_APPROVAL))
                .thenReturn(java.util.List.of(matching, other));

        var pending = service.pendingApprovalsForAdmin("lhc@iitk.ac.in", "Lecture Hall");

        assertEquals(1, pending.size());
        assertEquals("Lecture Hall", pending.get(0).getResource().getType());
    }

    @Test
    void adminApprovalDecisionConfirmsPendingBooking() {
        LabResource lectureHall = mock(LabResource.class);
        when(lectureHall.getType()).thenReturn("Lecture Hall");
        when(lectureHall.getBaseRate()).thenReturn(new BigDecimal("4"));
        when(lectureHall.getMinimumDurationHours()).thenReturn(new BigDecimal("1"));
        when(lectureHall.getMaximumContentionMultiplier()).thenReturn(new BigDecimal("3"));

        UUID bookingId = UUID.randomUUID();
        Booking pending = new Booking("member@iitk.ac.in", lectureHall,
                LocalDateTime.now().plusDays(2), LocalDateTime.now().plusDays(2).plusHours(1),
                BookingStatus.PENDING_APPROVAL, BigDecimal.ZERO);
        pending.setId(bookingId);

        when(bookingRepository.findByIdForUpdate(bookingId)).thenReturn(Optional.of(pending));
        when(bookingRepository.save(any(Booking.class))).thenAnswer(i -> i.getArgument(0));
        when(creditAccountRepository.findForUpdate("member@iitk.ac.in"))
                .thenReturn(Optional.of(new CreditAccount("member@iitk.ac.in", new BigDecimal("100"))));

        Booking approved = service.reviewApprovalDecision("lhc@iitk.ac.in", bookingId, true);

        assertEquals(BookingStatus.CONFIRMED, approved.getStatus());
        assertEquals(0, new BigDecimal("4").compareTo(approved.getCreditCost()));
    }

    @Test
    void pendingApprovalQueueOrdersByBookingDateAscending() {
        LabResource lectureHall = mock(LabResource.class);
        when(lectureHall.getType()).thenReturn("Lecture Hall");

        Booking later = new Booking("member2@iitk.ac.in", lectureHall,
                LocalDateTime.now().plusDays(4), LocalDateTime.now().plusDays(4).plusHours(1),
                BookingStatus.PENDING_APPROVAL, new BigDecimal("6"));
        Booking earlier = new Booking("member1@iitk.ac.in", lectureHall,
                LocalDateTime.now().plusDays(2), LocalDateTime.now().plusDays(2).plusHours(1),
                BookingStatus.PENDING_APPROVAL, new BigDecimal("5"));

        when(bookingRepository.findByStatusOrderByStartTimeAsc(BookingStatus.PENDING_APPROVAL))
                .thenReturn(java.util.List.of(later, earlier));

        var pending = service.pendingApprovalsForAdmin("lhc@iitk.ac.in", "Lecture Hall");

        assertEquals(2, pending.size());
        assertEquals(earlier.getStartTime(), pending.get(0).getStartTime());
        assertEquals(later.getStartTime(), pending.get(1).getStartTime());
    }

    @Test
    void pendingApprovalQueueOrdersSameResourceAndSlotBySubmissionTime() {
        LabResource lectureHall = mock(LabResource.class);
        when(lectureHall.getId()).thenReturn(UUID.randomUUID());
        when(lectureHall.getType()).thenReturn("Lecture Hall");

        LocalDateTime slotStart = LocalDateTime.now().plusDays(2).withHour(10).withMinute(0).withSecond(0).withNano(0);
        LocalDateTime slotEnd = slotStart.plusHours(1);

        Booking laterSubmission = new Booking("member2@iitk.ac.in", lectureHall,
                slotStart, slotEnd, BookingStatus.PENDING_APPROVAL, new BigDecimal("6"));
        laterSubmission.setCreatedAt(slotStart.minusHours(2));

        Booking earlierSubmission = new Booking("member1@iitk.ac.in", lectureHall,
                slotStart, slotEnd, BookingStatus.PENDING_APPROVAL, new BigDecimal("5"));
        earlierSubmission.setCreatedAt(slotStart.minusHours(4));

        when(bookingRepository.findByStatusOrderByStartTimeAsc(BookingStatus.PENDING_APPROVAL))
                .thenReturn(java.util.List.of(laterSubmission, earlierSubmission));

        var pending = service.pendingApprovalsForAdmin("lhc@iitk.ac.in", "Lecture Hall");

        assertEquals(2, pending.size());
        assertEquals(earlierSubmission.getCreatedAt(), pending.get(0).getCreatedAt());
        assertEquals(laterSubmission.getCreatedAt(), pending.get(1).getCreatedAt());
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
        lenient().when(creditAccountRepository.findForUpdate("member@iitk.ac.in")).thenReturn(Optional.of(account));

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

        CreditAccount account = new CreditAccount("member@iitk.ac.in", new BigDecimal("100"));
        when(creditAccountRepository.findForUpdate("member@iitk.ac.in")).thenReturn(Optional.of(account));

        Booking confirmed = service.confirmDraft("member@iitk.ac.in", draftId);

        assertEquals(draftId, confirmed.getId());
        assertEquals(BookingStatus.IN_PROGRESS, confirmed.getStatus());
        assertTrue(confirmed.getCreditCost().compareTo(BigDecimal.ZERO) > 0);
        verify(creditAccountRepository, org.mockito.Mockito.atLeastOnce()).findForUpdate(any());
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
    void confirmDraftSetsPendingApprovalForApprovalRequiredResource() {
        UUID draftId = UUID.randomUUID();
        var start = LocalDateTime.now().plusDays(1);
        var rule = new com.mrlbs.resource.BookingRule();
        rule.setBookingPolicy(com.mrlbs.resource.BookingPolicy.APPROVAL_REQUIRED);
        when(resource.getBookingRule()).thenReturn(rule);
        when(resourceRepository.findById(resourceId)).thenReturn(Optional.of(resource));

        Booking draft = new Booking("member@iitk.ac.in", resource, start, start.plusHours(1),
                BookingStatus.DRAFT, new BigDecimal("4"));
        draft.setId(draftId);
        when(bookingRepository.findById(draftId)).thenReturn(Optional.of(draft));
        when(bookingRepository.findOverlappingConfirmedForUpdate(any(), any(), any())).thenReturn(java.util.List.of());
        when(bookingRepository.save(any(Booking.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Booking confirmed = service.confirmDraft("member@iitk.ac.in", draftId);

        assertEquals(draftId, confirmed.getId());
        assertEquals(BookingStatus.PENDING_APPROVAL, confirmed.getStatus());
        assertEquals(BigDecimal.ZERO, confirmed.getCreditCost());
        verify(creditAccountRepository, never()).findForUpdate(any());
    }

    @Test
    void confirmDraftSetsPendingApprovalAndNoWaitlistForApprovalRequiredResourceEvenWhenSlotClashing() {
        UUID draftId = UUID.randomUUID();
        var start = LocalDateTime.now().plusDays(1);
        var rule = new com.mrlbs.resource.BookingRule();
        rule.setBookingPolicy(com.mrlbs.resource.BookingPolicy.APPROVAL_REQUIRED);
        when(resource.getBookingRule()).thenReturn(rule);
        when(resourceRepository.findById(resourceId)).thenReturn(Optional.of(resource));

        Booking draft = new Booking("member@iitk.ac.in", resource, start, start.plusHours(1),
                BookingStatus.DRAFT, new BigDecimal("4"));
        draft.setId(draftId);
        when(bookingRepository.findById(draftId)).thenReturn(Optional.of(draft));

        var existingBooking = new Booking("occupant@iitk.ac.in", resource, start, start.plusHours(1),
                BookingStatus.CONFIRMED, new BigDecimal("4"));
        when(bookingRepository.findOverlappingConfirmedForUpdate(any(), any(), any()))
                .thenReturn(java.util.List.of(existingBooking));
        when(bookingRepository.save(any(Booking.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Booking confirmed = service.confirmDraft("member@iitk.ac.in", draftId);

        assertEquals(draftId, confirmed.getId());
        assertEquals(BookingStatus.PENDING_APPROVAL, confirmed.getStatus());
        assertEquals(BigDecimal.ZERO, confirmed.getCreditCost());
        verify(creditAccountRepository, never()).findForUpdate(any());
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

    @Test
    void cancelConfirmedBookingPersistsCancelledStatusAndRefundsCredits() {
        UUID bookingId = UUID.randomUUID();
        UUID memberId = UUID.nameUUIDFromBytes("member@iitk.ac.in".getBytes());
        var start = LocalDateTime.now().plusDays(1);
        Booking confirmed = new Booking("member@iitk.ac.in", resource, start, start.plusHours(1),
                BookingStatus.CONFIRMED, new BigDecimal("4"));
        confirmed.setId(bookingId);
        confirmed.setMemberId(memberId);
        when(bookingRepository.findById(bookingId)).thenReturn(Optional.of(confirmed));
        when(bookingRepository.save(any(Booking.class))).thenAnswer(i -> i.getArgument(0));

        CreditAccount account = new CreditAccount("member@iitk.ac.in", new BigDecimal("6"));
        when(creditAccountRepository.findForUpdate("member@iitk.ac.in")).thenReturn(Optional.of(account));

        Booking cancelled = service.deleteBooking("member@iitk.ac.in", bookingId);

        assertEquals(BookingStatus.CANCELLED, cancelled.getStatus());
        org.junit.jupiter.api.Assertions.assertNotNull(cancelled.getCancelledAt());
        assertEquals(new BigDecimal("10"), account.getBalance());
        verify(creditAccountRepository).save(account);
        verify(bookingRepository).save(confirmed);
        verify(bookingRepository, never()).delete(any());
        verify(notificationRepository).save(any(com.mrlbs.notification.InAppNotification.class));
    }

    @Test
    void adminApprovalOccupiedSlotTransitionsToInProgressAndCreatesWaitlistEntry() {
        LabResource lectureHall = mock(LabResource.class);
        when(lectureHall.getType()).thenReturn("Lecture Hall");
        when(lectureHall.getBaseRate()).thenReturn(new BigDecimal("4"));
        when(lectureHall.getMinimumDurationHours()).thenReturn(new BigDecimal("1"));
        when(lectureHall.getMaximumContentionMultiplier()).thenReturn(new BigDecimal("3"));
        UUID resId = UUID.randomUUID();
        when(lectureHall.getId()).thenReturn(resId);

        UUID bookingId = UUID.randomUUID();
        UUID memberId = UUID.randomUUID();
        var start = LocalDateTime.now().plusDays(2);
        var end = start.plusHours(1);

        Booking pending = new Booking("member@iitk.ac.in", lectureHall, start, end,
                BookingStatus.PENDING_APPROVAL, BigDecimal.ZERO);
        pending.setId(bookingId);
        pending.setMemberId(memberId);

        when(bookingRepository.findByIdForUpdate(bookingId)).thenReturn(Optional.of(pending));
        when(bookingRepository.save(any(Booking.class))).thenAnswer(i -> i.getArgument(0));

        // Slot is already occupied by existing confirmed booking
        Booking existing = new Booking("occupant@iitk.ac.in", lectureHall, start, end,
                BookingStatus.CONFIRMED, new BigDecimal("4"));
        when(bookingRepository.findOverlappingConfirmedForUpdate(any(), any(), any()))
                .thenReturn(java.util.List.of(existing));

        CreditAccount account = new CreditAccount("member@iitk.ac.in", new BigDecimal("100"));
        when(creditAccountRepository.findForUpdate("member@iitk.ac.in")).thenReturn(Optional.of(account));
        when(waitlistRepository.findByBookingId(bookingId)).thenReturn(Optional.empty());

        Booking approved = service.reviewApprovalDecision("lhc@iitk.ac.in", bookingId, true);

        assertEquals(BookingStatus.IN_PROGRESS, approved.getStatus());
        assertTrue(approved.getCreditCost().compareTo(BigDecimal.ZERO) > 0);
        verify(waitlistRepository).save(any(com.mrlbs.waitlist.WaitlistEntry.class));
        verify(notificationRepository).save(any(com.mrlbs.notification.InAppNotification.class));
    }

    @Test
    void adminApprovalInsufficientCreditsThrowsException() {
        LabResource lectureHall = mock(LabResource.class);
        when(lectureHall.getType()).thenReturn("Lecture Hall");
        when(lectureHall.getBaseRate()).thenReturn(new BigDecimal("4"));
        when(lectureHall.getMinimumDurationHours()).thenReturn(new BigDecimal("1"));
        when(lectureHall.getMaximumContentionMultiplier()).thenReturn(new BigDecimal("3"));
        UUID resId = UUID.randomUUID();
        when(lectureHall.getId()).thenReturn(resId);

        UUID bookingId = UUID.randomUUID();
        var start = LocalDateTime.now().plusDays(2);
        var end = start.plusHours(1);

        Booking pending = new Booking("member@iitk.ac.in", lectureHall, start, end,
                BookingStatus.PENDING_APPROVAL, BigDecimal.ZERO);
        pending.setId(bookingId);

        when(bookingRepository.findByIdForUpdate(bookingId)).thenReturn(Optional.of(pending));
        when(bookingRepository.findOverlappingConfirmedForUpdate(any(), any(), any())).thenReturn(java.util.List.of());

        // Member only has 2 credits, cost will be 4
        CreditAccount account = new CreditAccount("member@iitk.ac.in", new BigDecimal("2"));
        when(creditAccountRepository.findForUpdate("member@iitk.ac.in")).thenReturn(Optional.of(account));

        var ex = assertThrows(BookingRejectedException.class,
                () -> service.reviewApprovalDecision("lhc@iitk.ac.in", bookingId, true));

        assertEquals(BookingRejectionReason.INSUFFICIENT_CREDITS, ex.getReason());
        verify(bookingRepository, never()).save(any());
    }

    @Test
    void adminApprovalPartialSlotConflictThrowsException() {
        LabResource lectureHall = mock(LabResource.class);
        when(lectureHall.getType()).thenReturn("Lecture Hall");
        UUID resId = UUID.randomUUID();
        when(lectureHall.getId()).thenReturn(resId);

        UUID bookingId = UUID.randomUUID();
        var start = LocalDateTime.now().plusDays(2);
        var end = start.plusHours(2);

        Booking pending = new Booking("member@iitk.ac.in", lectureHall, start, end,
                BookingStatus.PENDING_APPROVAL, BigDecimal.ZERO);
        pending.setId(bookingId);

        when(bookingRepository.findByIdForUpdate(bookingId)).thenReturn(Optional.of(pending));

        // Existing booking only overlaps partially (e.g. from start+30min to end+30min)
        Booking partialExisting = new Booking("occupant@iitk.ac.in", lectureHall,
                start.plusMinutes(30), end.plusMinutes(30), BookingStatus.CONFIRMED, new BigDecimal("4"));
        when(bookingRepository.findOverlappingConfirmedForUpdate(any(), any(), any()))
                .thenReturn(java.util.List.of(partialExisting));

        var ex = assertThrows(BookingRejectedException.class,
                () -> service.reviewApprovalDecision("lhc@iitk.ac.in", bookingId, true));

        assertEquals(BookingRejectionReason.SLOT_CONFLICT, ex.getReason());
        verify(creditAccountRepository, never()).findForUpdate(any());
    }

    @Test
    void adminApprovalBufferTimeViolationThrowsException() {
        LabResource lectureHall = mock(LabResource.class);
        when(lectureHall.getType()).thenReturn("Lecture Hall");
        UUID resId = UUID.randomUUID();
        when(lectureHall.getId()).thenReturn(resId);
        var rule = new com.mrlbs.resource.BookingRule();
        rule.setBufferTimeMinutes(30);
        when(lectureHall.getBookingRule()).thenReturn(rule);

        UUID bookingId = UUID.randomUUID();
        var start = LocalDateTime.now().plusDays(2);
        var end = start.plusHours(1);

        Booking pending = new Booking("member@iitk.ac.in", lectureHall, start, end,
                BookingStatus.PENDING_APPROVAL, BigDecimal.ZERO);
        pending.setId(bookingId);

        when(bookingRepository.findByIdForUpdate(bookingId)).thenReturn(Optional.of(pending));
        when(bookingRepository.findOverlappingConfirmedForUpdate(any(), any(), any())).thenReturn(java.util.List.of());

        // Confirmed booking within 30-min buffer window (e.g. ends 10 minutes before start)
        Booking adjacentBooking = new Booking("other@iitk.ac.in", lectureHall,
                start.minusMinutes(40), start.minusMinutes(10), BookingStatus.CONFIRMED, new BigDecimal("4"));
        when(bookingRepository.findConfirmedInWindow(any(), any(), any()))
                .thenReturn(java.util.List.of(adjacentBooking));

        var ex = assertThrows(BookingRejectedException.class,
                () -> service.reviewApprovalDecision("lhc@iitk.ac.in", bookingId, true));

        assertEquals(BookingRejectionReason.BUFFER_TIME_VIOLATION, ex.getReason());
        verify(creditAccountRepository, never()).findForUpdate(any());
    }

    @Test
    void adminRejectionWithReasonPersistsReasonAndSendsNotification() {
        LabResource lectureHall = mock(LabResource.class);
        when(lectureHall.getType()).thenReturn("Lecture Hall");
        when(lectureHall.getName()).thenReturn("LHC 101");

        UUID bookingId = UUID.randomUUID();
        UUID memberId = UUID.randomUUID();
        Booking pending = new Booking("member@iitk.ac.in", lectureHall,
                LocalDateTime.now().plusDays(2), LocalDateTime.now().plusDays(2).plusHours(1),
                BookingStatus.PENDING_APPROVAL, BigDecimal.ZERO);
        pending.setId(bookingId);
        pending.setMemberId(memberId);

        when(bookingRepository.findByIdForUpdate(bookingId)).thenReturn(Optional.of(pending));
        when(bookingRepository.save(any(Booking.class))).thenAnswer(i -> i.getArgument(0));

        Booking rejected = service.reviewApprovalDecision("lhc@iitk.ac.in", bookingId, false, "Resource maintenance scheduled.");

        assertEquals(BookingStatus.REJECTED, rejected.getStatus());
        assertEquals("Resource maintenance scheduled.", rejected.getRejectionReason());
        verify(notificationRepository).save(any(com.mrlbs.notification.InAppNotification.class));
        verify(creditAccountRepository, never()).findForUpdate(any());
    }

    @Test
    void adminRejectionWithoutReasonThrowsException() {
        LabResource lectureHall = mock(LabResource.class);
        when(lectureHall.getType()).thenReturn("Lecture Hall");

        UUID bookingId = UUID.randomUUID();
        Booking pending = new Booking("member@iitk.ac.in", lectureHall,
                LocalDateTime.now().plusDays(2), LocalDateTime.now().plusDays(2).plusHours(1),
                BookingStatus.PENDING_APPROVAL, BigDecimal.ZERO);
        pending.setId(bookingId);

        when(bookingRepository.findByIdForUpdate(bookingId)).thenReturn(Optional.of(pending));

        assertThrows(IllegalArgumentException.class,
                () -> service.reviewApprovalDecision("lhc@iitk.ac.in", bookingId, false, "   "));
    }

    @Test
    void cancelPendingApprovalBookingWithZeroCreditsDoesNotRefundOrSendNotification() {
        UUID bookingId = UUID.randomUUID();
        UUID memberId = UUID.nameUUIDFromBytes("member@iitk.ac.in".getBytes());
        var start = LocalDateTime.now().plusDays(1);
        Booking pending = new Booking("member@iitk.ac.in", resource, start, start.plusHours(1),
                BookingStatus.PENDING_APPROVAL, BigDecimal.ZERO);
        pending.setId(bookingId);
        pending.setMemberId(memberId);

        when(bookingRepository.findById(bookingId)).thenReturn(Optional.of(pending));
        when(bookingRepository.save(any(Booking.class))).thenAnswer(i -> i.getArgument(0));

        Booking cancelled = service.deleteBooking("member@iitk.ac.in", bookingId);

        assertEquals(BookingStatus.CANCELLED, cancelled.getStatus());
        verify(creditAccountRepository, never()).findForUpdate(any());
        verify(notificationRepository, never()).save(any());
    }

    @Test
    void adminApprovalIgnoresOtherPendingApprovalRequestsAndConfirmsWhenSlotAvailable() {
        LabResource lectureHall = mock(LabResource.class);
        when(lectureHall.getType()).thenReturn("Lecture Hall");
        when(lectureHall.getName()).thenReturn("LHC 101");
        when(lectureHall.getBaseRate()).thenReturn(new BigDecimal("10"));
        when(lectureHall.getMinimumDurationHours()).thenReturn(new BigDecimal("1"));
        when(lectureHall.getMaximumContentionMultiplier()).thenReturn(new BigDecimal("3.00"));
        BookingRule rule = new BookingRule();
        rule.setBookingPolicy(BookingPolicy.APPROVAL_REQUIRED);
        rule.setBufferTimeMinutes(15);
        when(lectureHall.getBookingRule()).thenReturn(rule);

        UUID bookingId1 = UUID.randomUUID();
        UUID bookingId2 = UUID.randomUUID();
        UUID memberId1 = UUID.randomUUID();
        UUID memberId2 = UUID.randomUUID();

        var start = LocalDateTime.now().plusDays(2).withHour(10).withMinute(0);
        var end = start.plusHours(1);

        Booking pending1 = new Booking("member1@iitk.ac.in", lectureHall, start, end,
                BookingStatus.PENDING_APPROVAL, BigDecimal.ZERO);
        pending1.setId(bookingId1);
        pending1.setMemberId(memberId1);

        Booking pending2 = new Booking("member2@iitk.ac.in", lectureHall, start.minusMinutes(30), end.plusMinutes(30),
                BookingStatus.PENDING_APPROVAL, BigDecimal.ZERO);
        pending2.setId(bookingId2);
        pending2.setMemberId(memberId2);

        when(bookingRepository.findByIdForUpdate(bookingId1)).thenReturn(Optional.of(pending1));
        // Simulate repository returning pending2 among overlapping/window bookings
        when(bookingRepository.findOverlappingConfirmedForUpdate(any(), any(), any()))
                .thenReturn(java.util.List.of(pending2));
        when(bookingRepository.findConfirmedInWindow(any(), any(), any()))
                .thenReturn(java.util.List.of(pending2));
        when(bookingRepository.save(any(Booking.class))).thenAnswer(i -> i.getArgument(0));

        CreditAccount account = new CreditAccount("member1@iitk.ac.in", new BigDecimal("50"));
        when(creditAccountRepository.findForUpdate("member1@iitk.ac.in")).thenReturn(Optional.of(account));

        // When admin approves booking 1
        Booking confirmed = service.reviewApprovalDecision("lhc@iitk.ac.in", bookingId1, true);

        // Then booking 1 must be CONFIRMED, not blocked by pending2
        assertEquals(BookingStatus.CONFIRMED, confirmed.getStatus());
        assertEquals(0, confirmed.getCreditCost().compareTo(new BigDecimal("10")));
        assertEquals(0, account.getBalance().compareTo(new BigDecimal("40")));
        verify(bookingRepository).save(pending1);
    }
}

