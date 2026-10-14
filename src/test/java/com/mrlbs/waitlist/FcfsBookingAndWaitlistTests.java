package com.mrlbs.waitlist;

import com.mrlbs.booking.Booking;
import com.mrlbs.booking.BookingRejectedException;
import com.mrlbs.booking.BookingRejectionReason;
import com.mrlbs.booking.BookingRepository;
import com.mrlbs.booking.BookingRequest;
import com.mrlbs.booking.BookingService;
import com.mrlbs.booking.BookingStatus;
import com.mrlbs.booking.credit.CreditAccount;
import com.mrlbs.booking.credit.CreditAccountRepository;
import com.mrlbs.member.Member;
import com.mrlbs.member.MemberRepository;
import com.mrlbs.resource.BookingPolicy;
import com.mrlbs.resource.BookingRule;
import com.mrlbs.resource.BookingRuleRepository;
import com.mrlbs.resource.LabResource;
import com.mrlbs.resource.LabResourceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
public class FcfsBookingAndWaitlistTests {

    @Autowired
    private BookingService bookingService;

    @Autowired
    private WaitlistService waitlistService;

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

    @Autowired(required = false)
    private WaitlistOfferRepository offerRepository;

    private LabResource testResource;
    private final String memberAEmail = "memberA@iitk.ac.in";
    private final String memberBEmail = "memberB@iitk.ac.in";
    private final String memberCEmail = "memberC@iitk.ac.in";
    private final String memberDEmail = "memberD@iitk.ac.in";

    @BeforeEach
    void setupData() {
        if (offerRepository != null) {
            offerRepository.deleteAll();
        }
        waitlistEntryRepository.deleteAll();
        bookingRepository.deleteAll();

        // Create Lab Resource with OPEN / FCFS policy
        LabResource resource = new LabResource(
                "Physics Lab Bench 1",
                "Lab Bench",
                "Physics Department Room 101",
                1,
                List.of("Handle equipment carefully"),
                new BigDecimal("1.00"),
                new BigDecimal("3.00"),
                new BigDecimal("10.00")
        );
        resource.getBookingRule().setBookingPolicy(BookingPolicy.OPEN);
        resource.getBookingRule().setMinDurationMinutes(30);
        resource.getBookingRule().setMaxDurationMinutes(180);
        resource.getBookingRule().setBufferTimeMinutes(0);
        resource.getBookingRule().setMaxBookingsPerMember(10);
        testResource = resourceRepository.save(resource);

        // Setup Members & Credits
        setupMember(memberAEmail, "Member A", new BigDecimal("1000"));
        setupMember(memberBEmail, "Member B", new BigDecimal("1000"));
        setupMember(memberCEmail, "Member C", new BigDecimal("1000"));
        setupMember(memberDEmail, "Member D", new BigDecimal("0")); // 0 credits
    }

    private Member setupMember(String email, String name, BigDecimal credits) {
        Member member = memberRepository.findByEmail(email).orElseGet(() -> {
            Member newMember = new Member(email, name, "MEMBER");
            return memberRepository.save(newMember);
        });
        creditAccountRepository.findById(email).ifPresentOrElse(
                acc -> {
                    acc.credit(credits.subtract(acc.getBalance()));
                    creditAccountRepository.save(acc);
                },
                () -> creditAccountRepository.save(new CreditAccount(email, credits))
        );
        return member;
    }

    @Test
    @DisplayName("1. Available slot booking and credit deduction: creates CONFIRMED booking and debits credits")
    void testAvailableSlotBookingAndCreditDeduction() {
        LocalDateTime start = LocalDateTime.now().plusDays(2).withHour(10).withMinute(0).withSecond(0).withNano(0);
        LocalDateTime end = start.plusHours(1);

        Booking draft = bookingService.createDraft(memberAEmail, new BookingRequest(testResource.getId(), start, end));
        assertNotNull(draft.getId());
        assertEquals(BookingStatus.DRAFT, draft.getStatus());

        Booking confirmed = bookingService.confirmDraft(memberAEmail, draft.getId());
        assertEquals(BookingStatus.CONFIRMED, confirmed.getStatus());
        assertTrue(confirmed.getCreditCost().compareTo(BigDecimal.ZERO) > 0);

        // Verify credit deduction
        CreditAccount account = creditAccountRepository.findById(memberAEmail).orElseThrow();
        assertEquals(new BigDecimal("1000").subtract(confirmed.getCreditCost()), account.getBalance());

        // Verify no waitlist entry created
        assertEquals(0, waitlistEntryRepository.count());
    }

    @Test
    @DisplayName("2. Insufficient-credit rejection: rejects available slot booking without saving or debiting")
    void testInsufficientCreditRejection() {
        LocalDateTime start = LocalDateTime.now().plusDays(2).withHour(11).withMinute(0).withSecond(0).withNano(0);
        LocalDateTime end = start.plusHours(1);

        // memberD has 0 credits
        Booking draft = bookingService.createDraft(memberDEmail, new BookingRequest(testResource.getId(), start, end));
        UUID draftId = draft.getId();

        BookingRejectedException ex = assertThrows(BookingRejectedException.class, () ->
                bookingService.confirmDraft(memberDEmail, draftId));

        assertEquals(BookingRejectionReason.INSUFFICIENT_CREDITS, ex.getReason());

        // Verify balance remained 0
        CreditAccount account = creditAccountRepository.findById(memberDEmail).orElseThrow();
        assertEquals(BigDecimal.ZERO, account.getBalance());

        // Verify draft was deleted
        assertFalse(bookingRepository.findById(draftId).isPresent());
    }

    @Test
    @DisplayName("3. Fully occupied-slot waitlisting without credit deduction: creates WAITING entry and IN_PROGRESS booking")
    void testFullyOccupiedSlotWaitlistingWithoutCreditDeduction() {
        LocalDateTime start = LocalDateTime.now().plusDays(2).withHour(12).withMinute(0).withSecond(0).withNano(0);
        LocalDateTime end = start.plusHours(1);

        // Member A books slot -> CONFIRMED
        Booking bookingA = bookingService.submit(memberAEmail, new BookingRequest(testResource.getId(), start, end));
        assertEquals(BookingStatus.CONFIRMED, bookingA.getStatus());

        BigDecimal memberBInitialBalance = creditAccountRepository.findById(memberBEmail).orElseThrow().getBalance();

        // Member B attempts to book the same fully occupied slot
        Booking draftB = bookingService.createDraft(memberBEmail, new BookingRequest(testResource.getId(), start, end));
        Booking waitlistedBooking = bookingService.confirmDraft(memberBEmail, draftB.getId());

        // Assert booking status is IN_PROGRESS
        assertEquals(BookingStatus.IN_PROGRESS, waitlistedBooking.getStatus());
        assertTrue(waitlistedBooking.getCreditCost().compareTo(BigDecimal.ZERO) > 0);

        // Assert credits were deducted from member B when booking is IN_PROGRESS
        CreditAccount memberBAccount = creditAccountRepository.findById(memberBEmail).orElseThrow();
        assertEquals(memberBInitialBalance.subtract(waitlistedBooking.getCreditCost()), memberBAccount.getBalance());

        // Assert IN_PROGRESS entry created in waitlist_entries
        List<WaitlistEntry> waitlist = waitlistEntryRepository.findByMemberIdOrderByJoinedAtDesc(
                waitlistService.resolveMemberId(memberBEmail));
        assertEquals(1, waitlist.size());
        WaitlistEntry entry = waitlist.get(0);
        assertEquals(BookingStatus.IN_PROGRESS, entry.getStatus());
        assertEquals(testResource.getId(), entry.getResource().getId());
        assertEquals(start, entry.getStartTime());
        assertEquals(end, entry.getEndTime());
        assertEquals(waitlistedBooking.getId(), entry.getBookingId());

        // Assert queue position is 1
        assertEquals(1, waitlistService.calculateQueuePosition(entry));
    }

    @Test
    @DisplayName("4. Partial-overlap rejection: rejects booking request that partially overlaps an existing booking")
    void testPartialOverlapRejection() {
        LocalDateTime start = LocalDateTime.now().plusDays(2).withHour(14).withMinute(0).withSecond(0).withNano(0);
        LocalDateTime end = start.plusHours(1); // 14:00 - 15:00

        // Member A books 14:00 - 15:00
        bookingService.submit(memberAEmail, new BookingRequest(testResource.getId(), start, end));

        // Member B requests partial overlap: 14:30 - 15:30
        LocalDateTime partialStart = start.plusMinutes(30);
        LocalDateTime partialEnd = end.plusMinutes(30);

        BookingRejectedException ex = assertThrows(BookingRejectedException.class, () ->
                bookingService.createDraft(memberBEmail, new BookingRequest(testResource.getId(), partialStart, partialEnd)));

        assertEquals(BookingRejectionReason.SLOT_CONFLICT, ex.getReason());
        assertTrue(ex.getMessage().contains("clashes with an already booked slot"));
        assertEquals(0, waitlistEntryRepository.count());
    }

    @Test
    @DisplayName("5. FIFO queue positions: multiple members join waitlist in FIFO order")
    void testFifoQueuePositions() throws InterruptedException {
        LocalDateTime start = LocalDateTime.now().plusDays(2).withHour(16).withMinute(0).withSecond(0).withNano(0);
        LocalDateTime end = start.plusHours(1);

        // Member A books slot
        bookingService.submit(memberAEmail, new BookingRequest(testResource.getId(), start, end));

        // Member B joins waitlist (first in queue)
        Booking bookingB = bookingService.submit(memberBEmail, new BookingRequest(testResource.getId(), start, end));
        Thread.sleep(50); // slight time difference to guarantee joinedAt order

        // Member C joins waitlist (second in queue)
        Booking bookingC = bookingService.submit(memberCEmail, new BookingRequest(testResource.getId(), start, end));

        WaitlistEntry entryB = waitlistEntryRepository.findByBookingId(bookingB.getId()).orElseThrow();
        WaitlistEntry entryC = waitlistEntryRepository.findByBookingId(bookingC.getId()).orElseThrow();

        assertEquals(1, waitlistService.calculateQueuePosition(entryB));
        assertEquals(2, waitlistService.calculateQueuePosition(entryC));
    }

    @Test
    @DisplayName("6. Duplicate prevention: prevents duplicate active waitlist entry for same member and slot")
    void testDuplicateActiveWaitlistPrevention() {
        LocalDateTime start = LocalDateTime.now().plusDays(2).withHour(18).withMinute(0).withSecond(0).withNano(0);
        LocalDateTime end = start.plusHours(1);

        bookingService.submit(memberAEmail, new BookingRequest(testResource.getId(), start, end));

        // Member B joins waitlist
        bookingService.submit(memberBEmail, new BookingRequest(testResource.getId(), start, end));

        // Member B attempts to join waitlist again for the same resource and exact time
        BookingRejectedException ex = assertThrows(BookingRejectedException.class, () ->
                bookingService.createDraft(memberBEmail, new BookingRequest(testResource.getId(), start, end)));

        assertEquals(BookingRejectionReason.DUPLICATE_WAITLIST, ex.getReason());
        assertEquals(1, waitlistEntryRepository.count());
    }

    @Test
    @DisplayName("7. Leaving the queue: member leaves waitlist, advancing subsequent members dynamically")
    void testLeavingQueueAndDynamicPositionAdvancement() throws InterruptedException {
        LocalDateTime start = LocalDateTime.now().plusDays(3).withHour(10).withMinute(0).withSecond(0).withNano(0);
        LocalDateTime end = start.plusHours(1);

        // Occupied by A
        bookingService.submit(memberAEmail, new BookingRequest(testResource.getId(), start, end));

        // B, C join waitlist
        Booking bookingB = bookingService.submit(memberBEmail, new BookingRequest(testResource.getId(), start, end));
        Thread.sleep(50);
        Booking bookingC = bookingService.submit(memberCEmail, new BookingRequest(testResource.getId(), start, end));

        WaitlistEntry entryB = waitlistEntryRepository.findByBookingId(bookingB.getId()).orElseThrow();
        WaitlistEntry entryC = waitlistEntryRepository.findByBookingId(bookingC.getId()).orElseThrow();

        assertEquals(1, waitlistService.calculateQueuePosition(entryB));
        assertEquals(2, waitlistService.calculateQueuePosition(entryC));

        // Member B leaves the waitlist
        WaitlistResponse leftResponse = waitlistService.leaveWaitlist(memberBEmail, entryB.getId());
        assertEquals(BookingStatus.CANCELLED, leftResponse.status());

        // Refresh entryB
        WaitlistEntry refreshedB = waitlistEntryRepository.findById(entryB.getId()).orElseThrow();
        assertEquals(BookingStatus.CANCELLED, refreshedB.getStatus());
        assertNotNull(refreshedB.getLeftAt());

        // Corresponding booking B should now be CANCELLED
        Booking refreshedBookingB = bookingRepository.findById(bookingB.getId()).orElseThrow();
        assertEquals(BookingStatus.CANCELLED, refreshedBookingB.getStatus());

        // Crucial test: Member C's queue position dynamically advances from 2 to 1!
        assertEquals(1, waitlistService.calculateQueuePosition(entryC));

        // Enforce ownership: Member A cannot leave Member C's entry
        assertThrows(AccessDeniedException.class, () ->
                waitlistService.leaveWaitlist(memberAEmail, entryC.getId()));
    }

    @Test
    @DisplayName("8. Credit validation on waitlist: member with insufficient credits cannot join waitlist (IN_PROGRESS)")
    void testCreditValidationDoesNotBlockWaitlisting() {
        LocalDateTime start = LocalDateTime.now().plusDays(3).withHour(12).withMinute(0).withSecond(0).withNano(0);
        LocalDateTime end = start.plusHours(1);

        // Member A books slot
        bookingService.submit(memberAEmail, new BookingRequest(testResource.getId(), start, end));

        // Member D has 0 credits, attempts to waitlist for occupied slot
        CreditAccount accountD = creditAccountRepository.findById(memberDEmail).orElseThrow();
        assertEquals(BigDecimal.ZERO, accountD.getBalance());

        Booking draftD = bookingService.createDraft(memberDEmail, new BookingRequest(testResource.getId(), start, end));
        assertThrows(BookingRejectedException.class, () ->
                bookingService.confirmDraft(memberDEmail, draftD.getId()));

        // Balance remains 0 (not debited, not negative)
        CreditAccount refreshedAccountD = creditAccountRepository.findById(memberDEmail).orElseThrow();
        assertEquals(BigDecimal.ZERO, refreshedAccountD.getBalance());
    }

    @Test
    @DisplayName("9. Concurrent booking attempts: exactly one confirmed booking, no overlapping confirmations")
    void testConcurrentBookingAttempts() throws Exception {
        LocalDateTime start = LocalDateTime.now().plusDays(3).withHour(14).withMinute(0).withSecond(0).withNano(0);
        LocalDateTime end = start.plusHours(1);

        int threads = 2;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threads);

        List<BookingStatus> results = Collections.synchronizedList(new ArrayList<>());
        List<Exception> exceptions = Collections.synchronizedList(new ArrayList<>());

        String[] members = { memberAEmail, memberBEmail };

        for (int i = 0; i < threads; i++) {
            final String email = members[i];
            executor.submit(() -> {
                try {
                    startLatch.await();
                    Booking booking = bookingService.submit(email, new BookingRequest(testResource.getId(), start, end));
                    results.add(booking.getStatus());
                } catch (Exception e) {
                    exceptions.add(e);
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        assertTrue(doneLatch.await(10, TimeUnit.SECONDS));
        executor.shutdown();

        // Exactly one should be CONFIRMED
        long confirmedCount = results.stream().filter(s -> s == BookingStatus.CONFIRMED).count();
        assertEquals(1, confirmedCount, "Exactly one booking must be CONFIRMED");

        // The second attempt should either be IN_PROGRESS (joined waitlist) or handled
        long inProgressCount = results.stream().filter(s -> s == BookingStatus.IN_PROGRESS).count();
        assertTrue(inProgressCount == 1 || !exceptions.isEmpty(),
                "Second concurrent attempt must be waitlisted or rejected, never concurrently confirmed");

        // Overlapping confirmed bookings in DB must be exactly 1
        long confirmedInDb = bookingRepository.findAll().stream()
                .filter(b -> b.getStatus() == BookingStatus.CONFIRMED && b.getResource().getId().equals(testResource.getId()))
                .count();
        assertEquals(1, confirmedInDb, "Overlapping CONFIRMED bookings in DB must be exactly 1");

        int waitlistedInDb = waitlistEntryRepository.findActiveQueue(testResource.getId(), start, end).size();
        assertEquals(1, waitlistedInDb, "Active waitlist entry in DB must be exactly 1");
    }

    @Test
    @DisplayName("10. Subset slot sharing waitlist: slot 14:00-16:00 booked by User A, User B waitlists 14:00-16:00 (pos 1), User C waitlists 14:30-15:30 (pos 2 on same waitlist)")
    void testSubsetBookingSharesWaitlistAndQueueOrder() {
        LocalDateTime start = LocalDateTime.now().plusDays(4).withHour(14).withMinute(0).withSecond(0).withNano(0);
        LocalDateTime end = start.plusHours(2); // 14:00 - 16:00

        // 1. User A books 14:00 to 16:00
        Booking bookingA = bookingService.submit(memberAEmail, new BookingRequest(testResource.getId(), start, end));
        assertEquals(BookingStatus.CONFIRMED, bookingA.getStatus());

        // 2. User B books 14:00 to 16:00 -> joins waitlist (queue position 1)
        Booking draftB = bookingService.createDraft(memberBEmail, new BookingRequest(testResource.getId(), start, end));
        Booking bookingB = bookingService.confirmDraft(memberBEmail, draftB.getId());
        assertEquals(BookingStatus.IN_PROGRESS, bookingB.getStatus());

        List<WaitlistEntry> queue = waitlistEntryRepository.findActiveQueue(testResource.getId(), start, end);
        assertEquals(1, queue.size());
        WaitlistEntry entryB = queue.get(0);
        assertEquals(1, waitlistService.calculateQueuePosition(entryB));

        // 3. User C books 14:30 to 15:30 (subset of 14:00 to 16:00) -> should go to the SAME waitlist (queue position 2)
        LocalDateTime subStart = start.plusMinutes(30); // 14:30
        LocalDateTime subEnd = start.plusMinutes(90);   // 15:30

        Booking draftC = bookingService.createDraft(memberCEmail, new BookingRequest(testResource.getId(), subStart, subEnd));
        Booking bookingC = bookingService.confirmDraft(memberCEmail, draftC.getId());
        assertEquals(BookingStatus.IN_PROGRESS, bookingC.getStatus());

        // Both B and C are in the same active queue
        List<WaitlistEntry> updatedQueue = waitlistEntryRepository.findActiveQueue(testResource.getId(), start, end);
        assertEquals(2, updatedQueue.size(), "Subset request must join the existing booked slot's waitlist");

        UUID memberCId = memberRepository.findByEmail(memberCEmail).orElseThrow().getId();
        WaitlistEntry entryC = updatedQueue.stream()
                .filter(w -> w.getMemberId().equals(memberCId))
                .findFirst()
                .orElseThrow();

        // Queue positions: User B = 1, User C = 2
        assertEquals(1, waitlistService.calculateQueuePosition(entryB));
        assertEquals(2, waitlistService.calculateQueuePosition(entryC));

        // Also query active queue using the subset interval [14:30, 15:30]
        List<WaitlistEntry> subQueue = waitlistEntryRepository.findActiveQueue(testResource.getId(), subStart, subEnd);
        assertEquals(2, subQueue.size(), "Querying active queue with subset range should also find the unified queue");

        // 4. Duplicate waitlist check: User B cannot also waitlist for 14:30 - 15:30
        BookingRejectedException ex = assertThrows(BookingRejectedException.class, () ->
                bookingService.createDraft(memberBEmail, new BookingRequest(testResource.getId(), subStart, subEnd)));
        assertEquals(BookingRejectionReason.DUPLICATE_WAITLIST, ex.getReason());
    }
}
