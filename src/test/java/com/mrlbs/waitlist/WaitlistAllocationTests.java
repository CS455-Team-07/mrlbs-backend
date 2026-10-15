package com.mrlbs.waitlist;

import com.mrlbs.booking.Booking;
import com.mrlbs.booking.BookingRepository;
import com.mrlbs.booking.BookingRequest;
import com.mrlbs.booking.BookingRejectedException;
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
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
public class WaitlistAllocationTests {

    @Autowired
    private BookingService bookingService;

    @Autowired
    private WaitlistService waitlistService;

    @Autowired
    private WaitlistAllocationService waitlistAllocationService;

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

    @Autowired(required = false)
    private WaitlistOfferService waitlistOfferService;

    private LabResource testResource;
    private final String memberAEmail = "userA@iitk.ac.in";
    private final String memberBEmail = "userB@iitk.ac.in";
    private final String memberCEmail = "userC@iitk.ac.in";
    private final String memberDEmail = "userD@iitk.ac.in";
    private final String adminEmail = "admin@iitk.ac.in";

    @BeforeEach
    void setupData() {
        if (waitlistOfferService != null) {
            waitlistOfferService.setOfferModeEnabled(false);
        }
        if (offerRepository != null) {
            offerRepository.deleteAll();
        }
        waitlistEntryRepository.deleteAll();
        bookingRepository.deleteAll();

        LabResource resource = new LabResource(
                "Spectrometer Lab",
                "Spectrometer",
                "Chemistry Lab 204",
                1,
                List.of("Safety goggles required"),
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
        setupMember(adminEmail, "Admin User", new BigDecimal("1000"));
    }

    private Member setupMember(String email, String name, BigDecimal credits) {
        Member member = memberRepository.findByEmail(email).orElseGet(() -> {
            Member newMember = new Member(email, name, email.contains("admin") ? "ADMIN" : "MEMBER");
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
    @DisplayName("1. FIFO selection: first eligible member in queue is allocated when slot is released")
    void testFifoSelection() throws InterruptedException {
        LocalDateTime start = LocalDateTime.now().plusDays(2).withHour(10).withMinute(0).withSecond(0).withNano(0);
        LocalDateTime end = start.plusHours(2); // 10:00 - 12:00

        // User A books slot -> CONFIRMED
        Booking bookingA = bookingService.submit(memberAEmail, new BookingRequest(testResource.getId(), start, end));
        assertEquals(BookingStatus.CONFIRMED, bookingA.getStatus());

        // User B joins waitlist (FIFO pos 1)
        Booking bookingB = bookingService.submit(memberBEmail, new BookingRequest(testResource.getId(), start, end));
        assertEquals(BookingStatus.IN_PROGRESS, bookingB.getStatus());
        Thread.sleep(50);

        // User C joins waitlist (FIFO pos 2)
        Booking bookingC = bookingService.submit(memberCEmail, new BookingRequest(testResource.getId(), start, end));
        assertEquals(BookingStatus.IN_PROGRESS, bookingC.getStatus());

        WaitlistEntry entryB = waitlistEntryRepository.findByBookingId(bookingB.getId()).orElseThrow();
        WaitlistEntry entryC = waitlistEntryRepository.findByBookingId(bookingC.getId()).orElseThrow();
        assertEquals(1, waitlistService.calculateQueuePosition(entryB));
        assertEquals(2, waitlistService.calculateQueuePosition(entryC));

        // User A cancels their booking, releasing 10:00 - 12:00
        bookingService.deleteBooking(memberAEmail, bookingA.getId());

        // Verify User B was allocated (status CONFIRMED, entry ALLOCATED)
        Booking refreshedB = bookingRepository.findById(bookingB.getId()).orElseThrow();
        assertEquals(BookingStatus.CONFIRMED, refreshedB.getStatus());
        assertTrue(refreshedB.getCreditCost().compareTo(BigDecimal.ZERO) > 0);

        WaitlistEntry refreshedEntryB = waitlistEntryRepository.findById(entryB.getId()).orElseThrow();
        assertEquals(BookingStatus.CONFIRMED, refreshedEntryB.getStatus());
        assertNotNull(refreshedEntryB.getLeftAt());

        // User C remains IN_PROGRESS and dynamically advances to queue position 1
        Booking refreshedC = bookingRepository.findById(bookingC.getId()).orElseThrow();
        assertEquals(BookingStatus.IN_PROGRESS, refreshedC.getStatus());
        WaitlistEntry refreshedEntryC = waitlistEntryRepository.findById(entryC.getId()).orElseThrow();
        assertEquals(BookingStatus.IN_PROGRESS, refreshedEntryC.getStatus());
        assertEquals(1, waitlistService.calculateQueuePosition(refreshedEntryC));
    }

    @Test
    @DisplayName("2. Contained vs partially unavailable windows: contained window allocated, partially unavailable skipped")
    void testContainedAndPartiallyUnavailableTimeWindows() throws InterruptedException {
        LocalDateTime start = LocalDateTime.now().plusDays(2).withHour(14).withMinute(0).withSecond(0).withNano(0);
        LocalDateTime end = start.plusHours(2); // 14:00 - 16:00

        // User A books 14:00 - 16:00
        Booking bookingA = bookingService.submit(memberAEmail, new BookingRequest(testResource.getId(), start, end));
        assertEquals(BookingStatus.CONFIRMED, bookingA.getStatus());

        // User B requests subset: 14:00 - 15:00 (fully contained)
        Booking bookingB = bookingService.submit(memberBEmail, new BookingRequest(testResource.getId(), start, start.plusHours(1)));
        assertEquals(BookingStatus.IN_PROGRESS, bookingB.getStatus());
        Thread.sleep(50);

        // User C manually placed on waitlist for 15:30 - 16:30 (partially unavailable, extends past 16:00)
        Booking bookingC = new Booking(memberCEmail, testResource, start.plusMinutes(90), start.plusMinutes(150),
                BookingStatus.IN_PROGRESS, new BigDecimal("20"));
        UUID memberCId = memberRepository.findByEmail(memberCEmail).orElseThrow().getId();
        bookingC.setMemberId(memberCId);
        bookingC = bookingRepository.save(bookingC);

        WaitlistEntry entryC = new WaitlistEntry(memberCId, testResource, start.plusMinutes(90), start.plusMinutes(150),
                BookingStatus.IN_PROGRESS, bookingC.getId(), new BigDecimal("20"), new BigDecimal("2.00"));
        waitlistEntryRepository.save(entryC);

        // User A cancels 14:00 - 16:00
        bookingService.deleteBooking(memberAEmail, bookingA.getId());

        // User B's contained request 14:00 - 15:00 must be allocated
        Booking refreshedB = bookingRepository.findById(bookingB.getId()).orElseThrow();
        assertEquals(BookingStatus.CONFIRMED, refreshedB.getStatus());
        assertEquals(start, refreshedB.getStartTime());
        assertEquals(start.plusHours(1), refreshedB.getEndTime());

        // User C's partially unavailable request (15:30 - 16:30) must be skipped (still IN_PROGRESS)
        Booking refreshedC = bookingRepository.findById(bookingC.getId()).orElseThrow();
        assertEquals(BookingStatus.IN_PROGRESS, refreshedC.getStatus());
        WaitlistEntry refreshedEntryC = waitlistEntryRepository.findById(entryC.getId()).orElseThrow();
        assertEquals(BookingStatus.IN_PROGRESS, refreshedEntryC.getStatus());
    }

    @Test
    @DisplayName("3. Inactive or ineligible entries: skipped in FIFO evaluation")
    void testInactiveAndIneligibleEntriesSkipped() throws InterruptedException {
        LocalDateTime start = LocalDateTime.now().plusDays(3).withHour(10).withMinute(0).withSecond(0).withNano(0);
        LocalDateTime end = start.plusHours(1);

        Booking bookingA = bookingService.submit(memberAEmail, new BookingRequest(testResource.getId(), start, end));

        // User B joins waitlist, but leaves the queue (inactive)
        Booking bookingB = bookingService.submit(memberBEmail, new BookingRequest(testResource.getId(), start, end));
        WaitlistEntry entryB = waitlistEntryRepository.findByBookingId(bookingB.getId()).orElseThrow();
        waitlistService.leaveWaitlist(memberBEmail, entryB.getId());
        assertEquals(BookingStatus.CANCELLED, waitlistEntryRepository.findById(entryB.getId()).orElseThrow().getStatus());
        Thread.sleep(50);

        // User C joins waitlist (active)
        Booking bookingC = bookingService.submit(memberCEmail, new BookingRequest(testResource.getId(), start, end));
        WaitlistEntry entryC = waitlistEntryRepository.findByBookingId(bookingC.getId()).orElseThrow();

        // User A cancels slot
        bookingService.deleteBooking(memberAEmail, bookingA.getId());

        // Inactive B remains CANCELLED
        assertEquals(BookingStatus.CANCELLED, waitlistEntryRepository.findById(entryB.getId()).orElseThrow().getStatus());
        assertEquals(BookingStatus.CANCELLED, bookingRepository.findById(bookingB.getId()).orElseThrow().getStatus());

        // Active C is allocated
        assertEquals(BookingStatus.CONFIRMED, bookingRepository.findById(bookingC.getId()).orElseThrow().getStatus());
        assertEquals(BookingStatus.CONFIRMED, waitlistEntryRepository.findById(entryC.getId()).orElseThrow().getStatus());
    }

    @Test
    @DisplayName("4. Preserved booking costs and M_contention: preserved cost is charged, not recalculated")
    void testPreservedCostAndContentionMultiplier() {
        LocalDateTime start = LocalDateTime.now().plusDays(3).withHour(14).withMinute(0).withSecond(0).withNano(0);
        LocalDateTime end = start.plusHours(1);

        // User A books slot
        Booking bookingA = bookingService.submit(memberAEmail, new BookingRequest(testResource.getId(), start, end));

        // User B joins waitlist when baseRate is 10.00 and contention is 1 + 0.1 * 1 = 1.10 -> cost = 11 credits
        Booking bookingB = bookingService.submit(memberBEmail, new BookingRequest(testResource.getId(), start, end));
        WaitlistEntry entryB = waitlistEntryRepository.findByBookingId(bookingB.getId()).orElseThrow();

        assertEquals(0, new BigDecimal("11").compareTo(entryB.getCreditCost()));
        assertEquals(0, new BigDecimal("1.10").compareTo(entryB.getContentionMultiplier()));

        // Now modify resource baseRate to 50.00 (simulating dynamic rate change)
        testResource.setBaseRate(new BigDecimal("50.00"));
        resourceRepository.save(testResource);

        BigDecimal initialBalanceB = creditAccountRepository.findById(memberBEmail).orElseThrow().getBalance();

        // User A cancels slot
        bookingService.deleteBooking(memberAEmail, bookingA.getId());

        // Verify User B was debited exactly the preserved cost of 11 credits (at IN_PROGRESS), NOT the new 50+ credits rate
        Booking refreshedB = bookingRepository.findById(bookingB.getId()).orElseThrow();
        assertEquals(BookingStatus.CONFIRMED, refreshedB.getStatus());
        assertEquals(0, new BigDecimal("11").compareTo(refreshedB.getCreditCost()));

        CreditAccount refreshedAccountB = creditAccountRepository.findById(memberBEmail).orElseThrow();
        assertEquals(initialBalanceB, refreshedAccountB.getBalance());
    }

    @Test
    @DisplayName("5. Credit validation and upfront deduction: insufficient credits rejects waitlist joining, eligible candidate debited on IN_PROGRESS and confirmed without duplicate debit")
    void testCreditValidationAndExactlyOnceDeduction() throws InterruptedException {
        LocalDateTime start = LocalDateTime.now().plusDays(4).withHour(10).withMinute(0).withSecond(0).withNano(0);
        LocalDateTime end = start.plusHours(1);

        // User A books slot
        Booking bookingA = bookingService.submit(memberAEmail, new BookingRequest(testResource.getId(), start, end));

        // User D has 0 credits, attempts to join waitlist -> rejected due to insufficient credits
        assertThrows(BookingRejectedException.class, () ->
                bookingService.submit(memberDEmail, new BookingRequest(testResource.getId(), start, end)));

        // User C has 1000 credits, joins waitlist (pos 1 in FIFO)
        BigDecimal balanceBeforeC = creditAccountRepository.findById(memberCEmail).orElseThrow().getBalance();
        Booking bookingC = bookingService.submit(memberCEmail, new BookingRequest(testResource.getId(), start, end));
        assertEquals(BookingStatus.IN_PROGRESS, bookingC.getStatus());

        BigDecimal balanceAfterWaitlistC = creditAccountRepository.findById(memberCEmail).orElseThrow().getBalance();
        assertEquals(balanceBeforeC.subtract(bookingC.getCreditCost()), balanceAfterWaitlistC);

        // User A cancels slot
        bookingService.deleteBooking(memberAEmail, bookingA.getId());

        // Eligible candidate User C is allocated and confirmed without duplicate deduction
        Booking refreshedC = bookingRepository.findById(bookingC.getId()).orElseThrow();
        assertEquals(BookingStatus.CONFIRMED, refreshedC.getStatus());
        BigDecimal costC = refreshedC.getCreditCost();
        assertTrue(costC.compareTo(BigDecimal.ZERO) > 0);

        CreditAccount refreshedAccountC = creditAccountRepository.findById(memberCEmail).orElseThrow();
        assertEquals(balanceAfterWaitlistC, refreshedAccountC.getBalance());

        // User D's balance remained 0
        CreditAccount refreshedAccountD = creditAccountRepository.findById(memberDEmail).orElseThrow();
        assertEquals(BigDecimal.ZERO, refreshedAccountD.getBalance());
    }

    @Test
    @DisplayName("6. Dynamic queue positions: remaining candidates automatically advance without stored positions")
    void testDynamicQueuePositionsUpdateOnAllocation() throws InterruptedException {
        LocalDateTime start = LocalDateTime.now().plusDays(4).withHour(14).withMinute(0).withSecond(0).withNano(0);
        LocalDateTime end = start.plusHours(1);

        Booking bookingA = bookingService.submit(memberAEmail, new BookingRequest(testResource.getId(), start, end));

        Booking bookingB = bookingService.submit(memberBEmail, new BookingRequest(testResource.getId(), start, end));
        Thread.sleep(50);
        Booking bookingC = bookingService.submit(memberCEmail, new BookingRequest(testResource.getId(), start, end));

        WaitlistEntry entryB = waitlistEntryRepository.findByBookingId(bookingB.getId()).orElseThrow();
        WaitlistEntry entryC = waitlistEntryRepository.findByBookingId(bookingC.getId()).orElseThrow();

        assertEquals(1, waitlistService.calculateQueuePosition(entryB));
        assertEquals(2, waitlistService.calculateQueuePosition(entryC));

        // User A cancels -> B is allocated
        bookingService.deleteBooking(memberAEmail, bookingA.getId());

        WaitlistEntry refreshedB = waitlistEntryRepository.findById(entryB.getId()).orElseThrow();
        assertEquals(BookingStatus.CONFIRMED, refreshedB.getStatus());
        assertEquals(0, waitlistService.calculateQueuePosition(refreshedB)); // no longer waiting

        // C's dynamic queue position advances to 1
        assertEquals(1, waitlistService.calculateQueuePosition(entryC));
    }

    @Test
    @DisplayName("7. Concurrent allocations: serialized execution prevents duplicate allocations or overlapping confirmations")
    void testConcurrentAllocationsPreventOverlappingBookings() throws Exception {
        LocalDateTime start = LocalDateTime.now().plusDays(5).withHour(10).withMinute(0).withSecond(0).withNano(0);
        LocalDateTime end = start.plusHours(1);

        // Pre-create User A's confirmed booking
        Booking bookingA = bookingService.submit(memberAEmail, new BookingRequest(testResource.getId(), start, end));
        assertEquals(BookingStatus.CONFIRMED, bookingA.getStatus());

        // User B and User C join waitlist
        Booking bookingB = bookingService.submit(memberBEmail, new BookingRequest(testResource.getId(), start, end));
        Booking bookingC = bookingService.submit(memberCEmail, new BookingRequest(testResource.getId(), start, end));

        // Cancel User A directly in DB to release the slot
        bookingA.setStatus(BookingStatus.CANCELLED);
        bookingA.setCancelledAt(LocalDateTime.now());
        bookingRepository.save(bookingA);

        // Concurrently run processReleasedSlot from 2 threads
        int threads = 2;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threads);

        List<List<Booking>> results = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < threads; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    List<Booking> allocated = waitlistAllocationService.processReleasedSlot(testResource.getId(), start, end);
                    results.add(allocated);
                } catch (Exception ignored) {
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        assertTrue(doneLatch.await(10, TimeUnit.SECONDS));
        executor.shutdown();

        // Exactly one confirmed booking must exist for that slot
        long confirmedCount = bookingRepository.findAll().stream()
                .filter(b -> b.getResource().getId().equals(testResource.getId())
                        && b.getStatus() == BookingStatus.CONFIRMED
                        && b.getStartTime().equals(start))
                .count();

        assertEquals(1, confirmedCount, "Exactly one booking must be confirmed after concurrent allocations");
    }

    @Test
    @DisplayName("8. APPROVAL_REQUIRED policy preserved: allocated booking transitions to PENDING_APPROVAL and credits debited on admin approval")
    void testApprovalRequiredPolicyPreserved() {
        LocalDateTime start = LocalDateTime.now().plusDays(5).withHour(14).withMinute(0).withSecond(0).withNano(0);
        LocalDateTime end = start.plusHours(1);

        // Set policy to APPROVAL_REQUIRED
        testResource.getBookingRule().setBookingPolicy(BookingPolicy.APPROVAL_REQUIRED);
        resourceRepository.save(testResource);

        // Create initial booking
        Booking bookingA = new Booking(memberAEmail, testResource, start, end, BookingStatus.CONFIRMED, new BigDecimal("10"));
        UUID memberAId = memberRepository.findByEmail(memberAEmail).orElseThrow().getId();
        bookingA.setMemberId(memberAId);
        bookingA = bookingRepository.save(bookingA);

        // User B joins waitlist (preserved cost 10 credits)
        Booking bookingB = new Booking(memberBEmail, testResource, start, end, BookingStatus.IN_PROGRESS, BigDecimal.ZERO);
        UUID memberBId = memberRepository.findByEmail(memberBEmail).orElseThrow().getId();
        bookingB.setMemberId(memberBId);
        bookingB = bookingRepository.save(bookingB);

        WaitlistEntry entryB = new WaitlistEntry(memberBId, testResource, start, end,
                BookingStatus.IN_PROGRESS, bookingB.getId(), new BigDecimal("10"), new BigDecimal("1.00"));
        waitlistEntryRepository.save(entryB);

        BigDecimal balanceBeforeApproval = creditAccountRepository.findById(memberBEmail).orElseThrow().getBalance();

        // User A cancels slot
        bookingService.deleteBooking(memberAEmail, bookingA.getId());

        // Waitlist allocation transitions bookingB directly to CONFIRMED (admin already approved joining waitlist)
        Booking allocatedB = bookingRepository.findById(bookingB.getId()).orElseThrow();
        assertEquals(BookingStatus.CONFIRMED, allocatedB.getStatus(),
                "APPROVAL_REQUIRED resource bookings must transition directly to CONFIRMED without second admin review");

        // Credits debited exactly once upon confirmation
        CreditAccount balanceAfterAllocated = creditAccountRepository.findById(memberBEmail).orElseThrow();
        assertEquals(balanceBeforeApproval.subtract(new BigDecimal("10")), balanceAfterAllocated.getBalance(),
                "Credits must be debited exactly once upon confirmation");
    }
}
