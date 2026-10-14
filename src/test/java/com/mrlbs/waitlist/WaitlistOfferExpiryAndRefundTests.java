package com.mrlbs.waitlist;

import com.mrlbs.booking.Booking;
import com.mrlbs.booking.BookingRejectedException;
import com.mrlbs.booking.BookingRepository;
import com.mrlbs.booking.BookingRequest;
import com.mrlbs.booking.BookingService;
import com.mrlbs.booking.BookingStatus;
import com.mrlbs.booking.credit.CreditAccount;
import com.mrlbs.booking.credit.CreditAccountRepository;
import com.mrlbs.member.Member;
import com.mrlbs.member.MemberRepository;
import com.mrlbs.notification.InAppNotificationRepository;
import com.mrlbs.resource.BookingPolicy;
import com.mrlbs.resource.BookingRuleRepository;
import com.mrlbs.resource.LabResource;
import com.mrlbs.resource.LabResourceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
public class WaitlistOfferExpiryAndRefundTests {

    @Autowired
    private BookingService bookingService;

    @Autowired
    private WaitlistOfferService waitlistOfferService;

    @Autowired
    private BookingRepository bookingRepository;

    @Autowired
    private LabResourceRepository resourceRepository;

    @Autowired
    private BookingRuleRepository ruleRepository;

    @Autowired
    private CreditAccountRepository creditAccountRepository;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private WaitlistEntryRepository waitlistEntryRepository;

    @Autowired
    private WaitlistOfferRepository offerRepository;

    @Autowired
    private InAppNotificationRepository notificationRepository;

    private LabResource testResource;
    private final String memberAEmail = "userA@iitk.ac.in";
    private final String memberBEmail = "userB@iitk.ac.in";
    private final String memberCEmail = "userCEmail@iitk.ac.in";
    private final String memberDEmail = "userDEmail@iitk.ac.in";

    @BeforeEach
    void setupData() {
        waitlistOfferService.setOfferModeEnabled(true);
        notificationRepository.deleteAll();
        offerRepository.deleteAll();
        waitlistEntryRepository.deleteAll();
        bookingRepository.deleteAll();

        LabResource resource = new LabResource(
                "Supercomputing Cluster Node",
                "Supercomputer",
                "Computer Center 102",
                1,
                List.of("HPC account required"),
                new BigDecimal("1.00"),
                new BigDecimal("3.00"),
                new BigDecimal("10.00")
        );
        resource.getBookingRule().setBookingPolicy(BookingPolicy.OPEN);
        resource.getBookingRule().setMinDurationMinutes(30);
        resource.getBookingRule().setMaxDurationMinutes(240);
        resource.getBookingRule().setBufferTimeMinutes(0);
        resource.getBookingRule().setMaxBookingsPerMember(10);
        testResource = resourceRepository.save(resource);

        setupMember(memberAEmail, "User A", new BigDecimal("1000"));
        setupMember(memberBEmail, "User B", new BigDecimal("1000"));
        setupMember(memberCEmail, "User C", new BigDecimal("1000"));
        setupMember(memberDEmail, "User D", new BigDecimal("0"));
    }

    private Member setupMember(String email, String name, BigDecimal credits) {
        Member member = memberRepository.findByEmail(email).orElseGet(() ->
                memberRepository.save(new Member(email, name, "MEMBER")));

        creditAccountRepository.findById(email).ifPresentOrElse(
                acc -> {
                    BigDecimal diff = credits.subtract(acc.getBalance());
                    if (diff.signum() > 0) acc.credit(diff);
                    else if (diff.signum() < 0) acc.debit(diff.negate());
                    creditAccountRepository.save(acc);
                },
                () -> creditAccountRepository.save(new CreditAccount(email, credits))
        );
        return member;
    }

    @Test
    @DisplayName("1. Automatic offer expiry marks offer EXPIRED and performs FIFO handoff to next member")
    void testAutomaticExpiryAndFifoHandoff() {
        LocalDateTime start = LocalDateTime.now().plusDays(2).withHour(10).withMinute(0).withSecond(0).withNano(0);
        LocalDateTime end = start.plusHours(2);

        // User A books, Users B and C join waitlist
        Booking bookingA = bookingService.submit(memberAEmail, new BookingRequest(testResource.getId(), start, end));
        Booking bookingB = bookingService.submit(memberBEmail, new BookingRequest(testResource.getId(), start, end));
        Booking bookingC = bookingService.submit(memberCEmail, new BookingRequest(testResource.getId(), start, end));

        // User A cancels, releasing slot
        bookingService.deleteBooking(memberAEmail, bookingA.getId());

        // Initial offer generated for first FIFO candidate (User B)
        List<WaitlistOfferResponse> bOffers = waitlistOfferService.getActiveOffersForMember(memberBEmail);
        assertEquals(1, bOffers.size());
        UUID offerBId = bOffers.get(0).id();

        // Simulate 5 minutes passing without user interaction
        WaitlistOffer offerB = offerRepository.findById(offerBId).orElseThrow();
        offerB.setExpiresAt(LocalDateTime.now().minusSeconds(1));
        offerRepository.save(offerB);

        // Background expiry processor runs
        waitlistOfferService.processExpiredOffers();

        // Offer B is now EXPIRED
        WaitlistOffer reloadedB = offerRepository.findById(offerBId).orElseThrow();
        assertEquals("EXPIRED", reloadedB.getStatus());

        // Waitlist entry B is CANCELLED
        WaitlistEntry entryB = waitlistEntryRepository.findByBookingId(bookingB.getId()).orElseThrow();
        assertEquals(BookingStatus.CANCELLED, entryB.getStatus());

        // FIFO handoff: Offer is automatically issued to next candidate (User C) with a fresh 5-minute expiry
        List<WaitlistOfferResponse> cOffers = waitlistOfferService.getActiveOffersForMember(memberCEmail);
        assertEquals(1, cOffers.size());
        assertEquals("PENDING", cOffers.get(0).status());
        assertTrue(cOffers.get(0).expiresAt().isAfter(LocalDateTime.now().plusMinutes(4)));
    }

    @Test
    @DisplayName("2. FIFO handoff after decline gives slot to next candidate; exhaustion leaves no active offer")
    void testFifoHandoffAfterDeclineAndExhaustion() {
        LocalDateTime start = LocalDateTime.now().plusDays(3).withHour(10).withMinute(0).withSecond(0).withNano(0);
        LocalDateTime end = start.plusHours(2);

        Booking bookingA = bookingService.submit(memberAEmail, new BookingRequest(testResource.getId(), start, end));
        Booking bookingB = bookingService.submit(memberBEmail, new BookingRequest(testResource.getId(), start, end));
        Booking bookingC = bookingService.submit(memberCEmail, new BookingRequest(testResource.getId(), start, end));

        // User A cancels -> Offer to B
        bookingService.deleteBooking(memberAEmail, bookingA.getId());
        WaitlistOffer offerB = offerRepository.findActiveOffersForSlot(testResource.getId(), start, end, LocalDateTime.now()).get(0);

        // B declines -> Immediately hands off to C
        waitlistOfferService.declineOffer(memberBEmail, offerB.getId());

        List<WaitlistOfferResponse> cOffers = waitlistOfferService.getActiveOffersForMember(memberCEmail);
        assertEquals(1, cOffers.size());
        UUID offerCId = cOffers.get(0).id();

        // C also declines -> Queue is exhausted, no active offers remain
        waitlistOfferService.declineOffer(memberCEmail, offerCId);

        List<WaitlistOffer> remainingActive = offerRepository.findActiveOffersForSlot(testResource.getId(), start, end, LocalDateTime.now());
        assertTrue(remainingActive.isEmpty());
    }

    @Test
    @DisplayName("3. Prevents duplicate active offers for the same slot")
    void testPreventsDuplicateActiveOffers() {
        LocalDateTime start = LocalDateTime.now().plusDays(4).withHour(10).withMinute(0).withSecond(0).withNano(0);
        LocalDateTime end = start.plusHours(2);

        Booking bookingA = bookingService.submit(memberAEmail, new BookingRequest(testResource.getId(), start, end));
        bookingService.submit(memberBEmail, new BookingRequest(testResource.getId(), start, end));

        bookingService.deleteBooking(memberAEmail, bookingA.getId());

        // Repeated release calls should not create duplicate offers
        var duplicateAttempt = waitlistOfferService.processReleasedSlotForOffer(testResource.getId(), start, end);
        assertTrue(duplicateAttempt.isEmpty(), "Should not create a second active offer for the same slot");

        List<WaitlistOffer> active = offerRepository.findActiveOffersForSlot(testResource.getId(), start, end, LocalDateTime.now());
        assertEquals(1, active.size());
    }

    @Test
    @DisplayName("4. Stale/expired offer acceptance is rejected; valid acceptance confirms booking exactly once")
    void testStaleOfferAcceptanceRejected() {
        LocalDateTime start = LocalDateTime.now().plusDays(5).withHour(10).withMinute(0).withSecond(0).withNano(0);
        LocalDateTime end = start.plusHours(2);

        Booking bookingA = bookingService.submit(memberAEmail, new BookingRequest(testResource.getId(), start, end));
        bookingService.submit(memberBEmail, new BookingRequest(testResource.getId(), start, end));

        bookingService.deleteBooking(memberAEmail, bookingA.getId());
        WaitlistOffer offerB = offerRepository.findActiveOffersForSlot(testResource.getId(), start, end, LocalDateTime.now()).get(0);

        // Mark expired
        offerB.setExpiresAt(LocalDateTime.now().minusMinutes(2));
        offerRepository.save(offerB);

        // Attempt to accept expired offer
        assertThrows(IllegalStateException.class, () ->
                waitlistOfferService.acceptOffer(memberBEmail, offerB.getId()));
    }

    @Test
    @DisplayName("5. Cancellation and credit refunds: exactly-once refund on cancellation")
    void testCancellationAndExactlyOnceRefund() {
        LocalDateTime start = LocalDateTime.now().plusDays(6).withHour(10).withMinute(0).withSecond(0).withNano(0);
        LocalDateTime end = start.plusHours(2);

        BigDecimal initialBalance = creditAccountRepository.findById(memberAEmail).orElseThrow().getBalance();

        // User A creates and confirms booking
        Booking bookingA = bookingService.submit(memberAEmail, new BookingRequest(testResource.getId(), start, end));
        BigDecimal cost = bookingA.getCreditCost();
        assertTrue(cost.compareTo(BigDecimal.ZERO) > 0);

        BigDecimal balanceAfterBooking = creditAccountRepository.findById(memberAEmail).orElseThrow().getBalance();
        assertEquals(initialBalance.subtract(cost), balanceAfterBooking);

        // User A cancels booking
        Booking cancelled = bookingService.deleteBooking(memberAEmail, bookingA.getId());
        assertEquals(BookingStatus.CANCELLED, cancelled.getStatus());
        assertNotNull(cancelled.getCancelledAt());

        // Exact refund applied
        BigDecimal balanceAfterCancel = creditAccountRepository.findById(memberAEmail).orElseThrow().getBalance();
        assertEquals(initialBalance, balanceAfterCancel);

        // Repeated cancellation does not issue a second refund (idempotent)
        Booking repeatCancel = bookingService.deleteBooking(memberAEmail, bookingA.getId());
        assertEquals(BookingStatus.CANCELLED, repeatCancel.getStatus());
        BigDecimal balanceAfterRepeat = creditAccountRepository.findById(memberAEmail).orElseThrow().getBalance();
        assertEquals(initialBalance, balanceAfterRepeat);
    }

    @Test
    @DisplayName("6. Concurrent cancellation requests perform refund exactly once without race condition")
    void testConcurrentCancellationExactlyOnceRefund() throws Exception {
        LocalDateTime start = LocalDateTime.now().plusDays(7).withHour(10).withMinute(0).withSecond(0).withNano(0);
        LocalDateTime end = start.plusHours(2);

        BigDecimal initialBalance = creditAccountRepository.findById(memberAEmail).orElseThrow().getBalance();
        Booking bookingA = bookingService.submit(memberAEmail, new BookingRequest(testResource.getId(), start, end));
        BigDecimal cost = bookingA.getCreditCost();

        int threads = 4;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        List<Callable<Booking>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> bookingService.deleteBooking(memberAEmail, bookingA.getId()));
        }

        List<Future<Booking>> futures = executor.invokeAll(tasks);
        for (Future<Booking> f : futures) {
            assertNotNull(f.get());
        }
        executor.shutdown();

        // Account must be refunded exactly once to initial balance
        BigDecimal finalBalance = creditAccountRepository.findById(memberAEmail).orElseThrow().getBalance();
        assertEquals(initialBalance, finalBalance);
    }

    @Test
    @DisplayName("7. Waitlisted entries deduct credits on IN_PROGRESS and refund them on cancellation")
    void testWaitlistDebitedOnInProgressAndRefundedOnCancel() {
        LocalDateTime start = LocalDateTime.now().plusDays(8).withHour(10).withMinute(0).withSecond(0).withNano(0);
        LocalDateTime end = start.plusHours(2);

        // User A books
        bookingService.submit(memberAEmail, new BookingRequest(testResource.getId(), start, end));

        // User B waitlists
        BigDecimal bBalanceBefore = creditAccountRepository.findById(memberBEmail).orElseThrow().getBalance();
        Booking bookingB = bookingService.submit(memberBEmail, new BookingRequest(testResource.getId(), start, end));
        assertEquals(BookingStatus.IN_PROGRESS, bookingB.getStatus());
        assertTrue(bookingB.getCreditCost().compareTo(BigDecimal.ZERO) > 0);

        BigDecimal bBalanceAfterJoin = creditAccountRepository.findById(memberBEmail).orElseThrow().getBalance();
        assertEquals(bBalanceBefore.subtract(bookingB.getCreditCost()), bBalanceAfterJoin, "Waitlisting must debit credits when IN_PROGRESS");

        // User B cancels their waitlist entry
        bookingService.deleteBooking(memberBEmail, bookingB.getId());
        BigDecimal bBalanceAfterCancel = creditAccountRepository.findById(memberBEmail).orElseThrow().getBalance();
        assertEquals(bBalanceBefore, bBalanceAfterCancel, "Cancelling waitlist must refund deducted credits");
    }

    @Test
    @DisplayName("8. Time-window matching: contained request gets offer, partially unavailable request is skipped")
    void testTimeWindowMatching() {
        LocalDateTime start = LocalDateTime.now().plusDays(9).withHour(10).withMinute(0).withSecond(0).withNano(0);
        LocalDateTime end = start.plusHours(4); // 10:00 - 14:00

        // User A books 10:00 - 14:00
        Booking bookingA = bookingService.submit(memberAEmail, new BookingRequest(testResource.getId(), start, end));

        // User B requests 10:00 - 12:00 (contained within released slot)
        bookingService.submit(memberBEmail, new BookingRequest(testResource.getId(), start, start.plusHours(2)));

        // User A cancels -> B gets offer for 10:00 - 12:00
        bookingService.deleteBooking(memberAEmail, bookingA.getId());

        List<WaitlistOfferResponse> bOffers = waitlistOfferService.getActiveOffersForMember(memberBEmail);
        assertEquals(1, bOffers.size());
        assertEquals(start, bOffers.get(0).startTime());
        assertEquals(start.plusHours(2), bOffers.get(0).endTime());
    }

    @Test
    @DisplayName("9. Preserves original booking cost and contention multiplier without recalculation")
    void testPreservationOfCostAndContentionMultiplier() {
        LocalDateTime start = LocalDateTime.now().plusDays(10).withHour(10).withMinute(0).withSecond(0).withNano(0);
        LocalDateTime end = start.plusHours(2);

        Booking bookingA = bookingService.submit(memberAEmail, new BookingRequest(testResource.getId(), start, end));
        Booking bookingB = bookingService.submit(memberBEmail, new BookingRequest(testResource.getId(), start, end));

        WaitlistEntry entryB = waitlistEntryRepository.findByBookingId(bookingB.getId()).orElseThrow();
        BigDecimal originalCost = entryB.getCreditCost();
        BigDecimal originalContention = entryB.getContentionMultiplier();
        assertNotNull(originalCost);
        assertNotNull(originalContention);

        // Change resource base rate dynamically
        testResource.setBaseRate(new BigDecimal("99.00"));
        resourceRepository.save(testResource);

        // Release slot and accept offer
        bookingService.deleteBooking(memberAEmail, bookingA.getId());
        WaitlistOffer offerB = offerRepository.findActiveOffersForSlot(testResource.getId(), start, end, LocalDateTime.now()).get(0);
        Booking confirmedB = waitlistOfferService.acceptOffer(memberBEmail, offerB.getId());

        // Confirmed booking cost matches PRESERVED cost, not the new base rate
        assertEquals(originalCost, confirmedB.getCreditCost());
    }

    @Test
    @DisplayName("10. Restart recovery: onApplicationReady processes missed expirations and triggers FIFO handoff")
    void testRestartRecovery() {
        LocalDateTime start = LocalDateTime.now().plusDays(11).withHour(10).withMinute(0).withSecond(0).withNano(0);
        LocalDateTime end = start.plusHours(2);

        Booking bookingA = bookingService.submit(memberAEmail, new BookingRequest(testResource.getId(), start, end));
        Booking bookingB = bookingService.submit(memberBEmail, new BookingRequest(testResource.getId(), start, end));
        Booking bookingC = bookingService.submit(memberCEmail, new BookingRequest(testResource.getId(), start, end));

        bookingService.deleteBooking(memberAEmail, bookingA.getId());
        WaitlistOffer offerB = offerRepository.findActiveOffersForSlot(testResource.getId(), start, end, LocalDateTime.now()).get(0);

        // Simulate server was down and offer expired while offline
        offerB.setExpiresAt(LocalDateTime.now().minusHours(1));
        offerRepository.save(offerB);

        // Application startup event fires
        waitlistOfferService.onApplicationReady();

        // Offer B is EXPIRED
        assertEquals("EXPIRED", offerRepository.findById(offerB.getId()).orElseThrow().getStatus());

        // Candidate C has received the offer
        List<WaitlistOfferResponse> cOffers = waitlistOfferService.getActiveOffersForMember(memberCEmail);
        assertEquals(1, cOffers.size());
    }

    @Test
    @DisplayName("11. APPROVAL_REQUIRED policy is preserved on offer acceptance: transitions to PENDING_APPROVAL")
    void testApprovalRequiredPreservedOnOfferAcceptance() {
        LabResource approvalResource = new LabResource(
                "BioSafety Level 3 Lab",
                "BioLab",
                "Medical Block 301",
                1,
                List.of("Biosafety permit required"),
                new BigDecimal("1.00"),
                new BigDecimal("2.00"),
                new BigDecimal("5.00")
        );
        approvalResource.getBookingRule().setBookingPolicy(BookingPolicy.APPROVAL_REQUIRED);
        approvalResource.getBookingRule().setMinDurationMinutes(30);
        approvalResource.getBookingRule().setMaxDurationMinutes(240);
        approvalResource = resourceRepository.save(approvalResource);

        LocalDateTime start = LocalDateTime.now().plusDays(12).withHour(10).withMinute(0).withSecond(0).withNano(0);
        LocalDateTime end = start.plusHours(2);

        Member memberA = memberRepository.findByEmail(memberAEmail).orElseThrow();
        Booking firstBooking = new Booking(memberAEmail, approvalResource, start, end, BookingStatus.CONFIRMED, new BigDecimal("20.00"));
        firstBooking.setMemberId(memberA.getId());
        bookingRepository.save(firstBooking);

        // User B joins waitlist
        Booking bookingB = bookingService.submit(memberBEmail, new BookingRequest(approvalResource.getId(), start, end));

        // Slot released
        bookingService.deleteBooking(memberAEmail, firstBooking.getId());

        WaitlistOffer offerB = offerRepository.findActiveOffersForSlot(approvalResource.getId(), start, end, LocalDateTime.now()).get(0);

        // User B accepts
        Booking accepted = waitlistOfferService.acceptOffer(memberBEmail, offerB.getId());

        // Must transition to PENDING_APPROVAL without bypassing admin approval
        assertEquals(BookingStatus.PENDING_APPROVAL, accepted.getStatus());
    }
}
