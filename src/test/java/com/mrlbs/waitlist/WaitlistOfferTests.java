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
import com.mrlbs.notification.InAppNotification;
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
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
public class WaitlistOfferTests {

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
    private final String memberCEmail = "userC@iitk.ac.in";
    private final String memberDEmail = "userD@iitk.ac.in";

    @BeforeEach
    void setupData() {
        waitlistOfferService.setOfferModeEnabled(true);
        notificationRepository.deleteAll();
        offerRepository.deleteAll();
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
    @DisplayName("Offer creation sets 5-minute expiry timestamp and sends in-app notification")
    void testOfferCreationAndFiveMinuteExpiry() {
        LocalDateTime start = LocalDateTime.now().plusDays(2).withHour(10).withMinute(0).withSecond(0).withNano(0);
        LocalDateTime end = start.plusHours(2);

        // User A books the slot
        Booking bookingA = bookingService.submit(memberAEmail, new BookingRequest(testResource.getId(), start, end));

        // User B waitlists the slot
        bookingService.submit(memberBEmail, new BookingRequest(testResource.getId(), start, end));

        // Slot is released (User A cancels)
        bookingRepository.delete(bookingA);

        // Released slot triggers offer creation
        Optional<WaitlistOffer> offerOpt = waitlistOfferService.processReleasedSlotForOffer(
                testResource.getId(), start, end);

        assertTrue(offerOpt.isPresent(), "An offer should have been created for User B");
        WaitlistOffer offer = offerOpt.get();

        assertEquals("PENDING", offer.getStatus());
        assertNotNull(offer.getOfferedAt());
        assertNotNull(offer.getExpiresAt());

        // Verify exact 5-minute expiry
        long minutesBetween = Duration.between(offer.getOfferedAt(), offer.getExpiresAt()).toMinutes();
        assertEquals(5, minutesBetween, "Offer expiry must be exactly 5 minutes after creation");

        // Verify in-app notification
        Member userB = memberRepository.findByEmail(memberBEmail).orElseThrow();
        List<InAppNotification> notifications = notificationRepository.findByMemberIdOrderByCreatedAtDesc(userB.getId());
        assertEquals(1, notifications.size(), "Member B should receive exactly one in-app notification");

        InAppNotification notification = notifications.get(0);
        assertFalse(notification.isRead());
        assertEquals(offer.getId(), notification.getOfferId());
        assertTrue(notification.getTitle().contains("Spectrometer Lab"));
    }

    @Test
    @DisplayName("Prevents multiple active offers for the same released slot simultaneously")
    void testDuplicateOfferPrevention() {
        LocalDateTime start = LocalDateTime.now().plusDays(2).withHour(14).withMinute(0).withSecond(0).withNano(0);
        LocalDateTime end = start.plusHours(2);

        Booking bookingA = bookingService.submit(memberAEmail, new BookingRequest(testResource.getId(), start, end));
        bookingService.submit(memberBEmail, new BookingRequest(testResource.getId(), start, end));

        bookingRepository.delete(bookingA);

        // First offer created
        Optional<WaitlistOffer> firstOffer = waitlistOfferService.processReleasedSlotForOffer(
                testResource.getId(), start, end);
        assertTrue(firstOffer.isPresent());

        // Try evaluating the same slot again
        Optional<WaitlistOffer> duplicateOffer = waitlistOfferService.processReleasedSlotForOffer(
                testResource.getId(), start, end);
        assertTrue(duplicateOffer.isEmpty(), "Should not create a second active offer for the same slot");
    }

    @Test
    @DisplayName("Authorized acceptance deducts credits exactly once using preserved cost and updates booking to CONFIRMED")
    void testAuthorizedAcceptanceAndCreditDeduction() {
        LocalDateTime start = LocalDateTime.now().plusDays(3).withHour(10).withMinute(0).withSecond(0).withNano(0);
        LocalDateTime end = start.plusHours(2);

        BigDecimal initialBalanceB = creditAccountRepository.findById(memberBEmail).orElseThrow().getBalance();
        Booking bookingA = bookingService.submit(memberAEmail, new BookingRequest(testResource.getId(), start, end));
        Booking bBooking = bookingService.submit(memberBEmail, new BookingRequest(testResource.getId(), start, end));

        WaitlistEntry entry = waitlistEntryRepository.findByBookingId(bBooking.getId()).orElseThrow();
        BigDecimal preservedCost = entry.getCreditCost();
        assertNotNull(preservedCost);
        assertTrue(preservedCost.compareTo(BigDecimal.ZERO) > 0);

        bookingRepository.delete(bookingA);

        Optional<WaitlistOffer> offerOpt = waitlistOfferService.processReleasedSlotForOffer(
                testResource.getId(), start, end);
        assertTrue(offerOpt.isPresent());
        WaitlistOffer offer = offerOpt.get();

        BigDecimal balanceBefore = creditAccountRepository.findById(memberBEmail).orElseThrow().getBalance();

        // User B accepts the offer
        Booking confirmedBooking = waitlistOfferService.acceptOffer(memberBEmail, offer.getId());

        assertEquals(BookingStatus.CONFIRMED, confirmedBooking.getStatus());
        assertEquals(preservedCost, confirmedBooking.getCreditCost());

        // Check credit account deducted exactly once (at IN_PROGRESS, not double-deducted on accept)
        BigDecimal balanceAfter = creditAccountRepository.findById(memberBEmail).orElseThrow().getBalance();
        assertEquals(initialBalanceB.subtract(preservedCost), balanceAfter, "Credits should be deducted exactly once by preserved cost");
        assertEquals(balanceBefore, balanceAfter, "Credits should not be double deducted when accepting offer");

        // Check offer status
        WaitlistOffer updatedOffer = offerRepository.findById(offer.getId()).orElseThrow();
        assertEquals("ACCEPTED", updatedOffer.getStatus());
        assertNotNull(updatedOffer.getRespondedAt());
        assertEquals(confirmedBooking.getId(), updatedOffer.getResultingBookingId());

        // Check waitlist entry
        WaitlistEntry updatedEntry = waitlistEntryRepository.findById(entry.getId()).orElseThrow();
        assertEquals(BookingStatus.CONFIRMED, updatedEntry.getStatus());
        assertNotNull(updatedEntry.getLeftAt());

        // Accepting again must fail
        assertThrows(IllegalStateException.class, () ->
                waitlistOfferService.acceptOffer(memberBEmail, offer.getId()));
    }

    @Test
    @DisplayName("Unauthorized member cannot accept or decline another member's offer")
    void testUnauthorizedUserCannotAcceptOrDecline() {
        LocalDateTime start = LocalDateTime.now().plusDays(3).withHour(14).withMinute(0).withSecond(0).withNano(0);
        LocalDateTime end = start.plusHours(2);

        Booking bookingA = bookingService.submit(memberAEmail, new BookingRequest(testResource.getId(), start, end));
        bookingService.submit(memberBEmail, new BookingRequest(testResource.getId(), start, end));

        bookingRepository.delete(bookingA);

        WaitlistOffer offer = waitlistOfferService.processReleasedSlotForOffer(testResource.getId(), start, end).orElseThrow();

        // User C attempts to accept User B's offer
        assertThrows(AccessDeniedException.class, () ->
                waitlistOfferService.acceptOffer(memberCEmail, offer.getId()));

        // User C attempts to decline User B's offer
        assertThrows(AccessDeniedException.class, () ->
                waitlistOfferService.declineOffer(memberCEmail, offer.getId()));
    }

    @Test
    @DisplayName("Declining an offer records response time, marks offer DECLINED, and cancels booking")
    void testDeclineOffer() {
        LocalDateTime start = LocalDateTime.now().plusDays(4).withHour(10).withMinute(0).withSecond(0).withNano(0);
        LocalDateTime end = start.plusHours(2);

        BigDecimal initialBalanceB = creditAccountRepository.findById(memberBEmail).orElseThrow().getBalance();

        Booking bookingA = bookingService.submit(memberAEmail, new BookingRequest(testResource.getId(), start, end));
        Booking bBooking = bookingService.submit(memberBEmail, new BookingRequest(testResource.getId(), start, end));

        bookingRepository.delete(bookingA);

        WaitlistOffer offer = waitlistOfferService.processReleasedSlotForOffer(testResource.getId(), start, end).orElseThrow();

        // User B was debited at IN_PROGRESS
        BigDecimal balanceDuringWaitlist = creditAccountRepository.findById(memberBEmail).orElseThrow().getBalance();
        assertEquals(initialBalanceB.subtract(bBooking.getCreditCost()), balanceDuringWaitlist);

        // User B declines
        WaitlistOffer declined = waitlistOfferService.declineOffer(memberBEmail, offer.getId());

        assertEquals("DECLINED", declined.getStatus());
        assertNotNull(declined.getRespondedAt());

        // Credits refunded upon cancellation
        BigDecimal balanceAfter = creditAccountRepository.findById(memberBEmail).orElseThrow().getBalance();
        assertEquals(initialBalanceB, balanceAfter, "Credits should be refunded when waitlist booking is cancelled upon decline");

        // Associated booking cancelled
        Booking updatedBooking = bookingRepository.findById(bBooking.getId()).orElseThrow();
        assertEquals(BookingStatus.CANCELLED, updatedBooking.getStatus());

        // Associated waitlist entry CANCELLED
        WaitlistEntry entry = waitlistEntryRepository.findByBookingId(bBooking.getId()).orElseThrow();
        assertEquals(BookingStatus.CANCELLED, entry.getStatus());
    }

    @Test
    @DisplayName("Expired offers cannot be accepted and are marked EXPIRED")
    void testExpiredOfferCannotBeAccepted() {
        LocalDateTime start = LocalDateTime.now().plusDays(4).withHour(14).withMinute(0).withSecond(0).withNano(0);
        LocalDateTime end = start.plusHours(2);

        Booking bookingA = bookingService.submit(memberAEmail, new BookingRequest(testResource.getId(), start, end));
        bookingService.submit(memberBEmail, new BookingRequest(testResource.getId(), start, end));

        bookingRepository.delete(bookingA);

        WaitlistOffer offer = waitlistOfferService.processReleasedSlotForOffer(testResource.getId(), start, end).orElseThrow();

        // Simulate expiry in the past
        offer.setExpiresAt(LocalDateTime.now().minusMinutes(1));
        offerRepository.save(offer);

        BigDecimal balanceBefore = creditAccountRepository.findById(memberBEmail).orElseThrow().getBalance();

        // User B attempts to accept
        assertThrows(IllegalStateException.class, () ->
                waitlistOfferService.acceptOffer(memberBEmail, offer.getId()));

        // Offer is marked EXPIRED
        WaitlistOffer reloadedOffer = offerRepository.findById(offer.getId()).orElseThrow();
        assertEquals("EXPIRED", reloadedOffer.getStatus());

        // Credits not deducted
        BigDecimal balanceAfter = creditAccountRepository.findById(memberBEmail).orElseThrow().getBalance();
        assertEquals(balanceBefore, balanceAfter);
    }

    @Test
    @DisplayName("Preserves original booking cost and contention multiplier even if other members join queue")
    void testPreservedCostAndContention() {
        LocalDateTime start = LocalDateTime.now().plusDays(5).withHour(10).withMinute(0).withSecond(0).withNano(0);
        LocalDateTime end = start.plusHours(2);

        BigDecimal initialBalanceB = creditAccountRepository.findById(memberBEmail).orElseThrow().getBalance();

        // User A books
        Booking bookingA = bookingService.submit(memberAEmail, new BookingRequest(testResource.getId(), start, end));

        // User B joins waitlist (queue position 1 -> contention = 1 + 0.1 * 1 = 1.10)
        Booking bBooking = bookingService.submit(memberBEmail, new BookingRequest(testResource.getId(), start, end));
        WaitlistEntry bEntry = waitlistEntryRepository.findByBookingId(bBooking.getId()).orElseThrow();
        BigDecimal bPreservedCost = bEntry.getCreditCost();
        BigDecimal bPreservedMultiplier = bEntry.getContentionMultiplier();

        // User C joins waitlist (queue position 2 -> contention = 1.20)
        bookingService.submit(memberCEmail, new BookingRequest(testResource.getId(), start, end));

        bookingRepository.delete(bookingA);

        // Slot released and offered to User B
        WaitlistOffer offer = waitlistOfferService.processReleasedSlotForOffer(testResource.getId(), start, end).orElseThrow();

        // User B accepts
        Booking confirmed = waitlistOfferService.acceptOffer(memberBEmail, offer.getId());

        // The confirmed booking cost matches the PRESERVED cost, not any new cost
        assertEquals(bPreservedCost, confirmed.getCreditCost());
        BigDecimal balanceAfter = creditAccountRepository.findById(memberBEmail).orElseThrow().getBalance();
        assertEquals(initialBalanceB.subtract(bPreservedCost), balanceAfter);
    }

    @Test
    @DisplayName("Preserves APPROVAL_REQUIRED policy: booking transitions to PENDING_APPROVAL without bypassing admin")
    void testApprovalRequiredPreservedOnOfferAcceptance() {
        // Create resource requiring approval
        LabResource approvalResource = new LabResource(
                "Cleanroom Facility",
                "Cleanroom",
                "Nano Center 101",
                1,
                List.of("Cert required"),
                new BigDecimal("1.00"),
                new BigDecimal("2.00"),
                new BigDecimal("5.00")
        );
        approvalResource.getBookingRule().setBookingPolicy(BookingPolicy.APPROVAL_REQUIRED);
        approvalResource.getBookingRule().setMinDurationMinutes(30);
        approvalResource.getBookingRule().setMaxDurationMinutes(240);
        approvalResource = resourceRepository.save(approvalResource);

        LocalDateTime start = LocalDateTime.now().plusDays(6).withHour(10).withMinute(0).withSecond(0).withNano(0);
        LocalDateTime end = start.plusHours(2);

        Member memberA = memberRepository.findByEmail(memberAEmail).orElseThrow();
        Booking firstBooking = new Booking(memberAEmail, approvalResource, start, end, BookingStatus.CONFIRMED, new BigDecimal("20.00"));
        firstBooking.setMemberId(memberA.getId());
        bookingRepository.save(firstBooking);

        // User B waitlists
        Booking bBooking = bookingService.submit(memberBEmail, new BookingRequest(approvalResource.getId(), start, end));

        // Slot released
        bookingRepository.delete(firstBooking);

        WaitlistOffer offer = waitlistOfferService.processReleasedSlotForOffer(approvalResource.getId(), start, end).orElseThrow();

        // User B accepts
        Booking acceptedBooking = waitlistOfferService.acceptOffer(memberBEmail, offer.getId());

        // Must be PENDING_APPROVAL, not CONFIRMED, respecting APPROVAL_REQUIRED policy
        assertEquals(BookingStatus.PENDING_APPROVAL, acceptedBooking.getStatus());
    }

    @Test
    @DisplayName("Insufficient credits prevents joining waitlist in IN_PROGRESS state")
    void testInsufficientCreditsRejection() {
        LocalDateTime start = LocalDateTime.now().plusDays(7).withHour(10).withMinute(0).withSecond(0).withNano(0);
        LocalDateTime end = start.plusHours(2);

        Booking bookingA = bookingService.submit(memberAEmail, new BookingRequest(testResource.getId(), start, end));

        // User D has 0 credits and attempts to join waitlist -> rejected immediately because IN_PROGRESS debits credits
        assertThrows(BookingRejectedException.class, () ->
                bookingService.submit(memberDEmail, new BookingRequest(testResource.getId(), start, end)));
    }

    @Test
    @DisplayName("Declining an offer immediately triggers offer creation for next eligible waitlist candidate")
    void testDeclineTriggersNextEligibleOffer() {
        LocalDateTime start = LocalDateTime.now().plusDays(8).withHour(10).withMinute(0).withSecond(0).withNano(0);
        LocalDateTime end = start.plusHours(2);

        Booking bookingA = bookingService.submit(memberAEmail, new BookingRequest(testResource.getId(), start, end));
        Booking bookingB = bookingService.submit(memberBEmail, new BookingRequest(testResource.getId(), start, end));
        Booking bookingC = bookingService.submit(memberCEmail, new BookingRequest(testResource.getId(), start, end));

        // Release slot
        bookingRepository.delete(bookingA);

        // Initial offer to B
        WaitlistOffer offerB = waitlistOfferService.processReleasedSlotForOffer(testResource.getId(), start, end).orElseThrow();
        UUID memberBId = memberRepository.findByEmail(memberBEmail).orElseThrow().getId();
        assertEquals(memberBId, offerB.getWaitlistEntry().getMemberId());

        // B declines
        waitlistOfferService.declineOffer(memberBEmail, offerB.getId());

        // C should now have an active offer automatically!
        List<WaitlistOfferResponse> activeForC = waitlistOfferService.getActiveOffersForMember(memberCEmail);
        assertEquals(1, activeForC.size());
        assertEquals("PENDING", activeForC.get(0).status());
    }

    @Test
    @DisplayName("Expired offer marks offer EXPIRED and re-runs FIFO allocation for next eligible candidate")
    void testExpiryTriggersNextEligibleOffer() {
        LocalDateTime start = LocalDateTime.now().plusDays(9).withHour(10).withMinute(0).withSecond(0).withNano(0);
        LocalDateTime end = start.plusHours(2);

        Booking bookingA = bookingService.submit(memberAEmail, new BookingRequest(testResource.getId(), start, end));
        Booking bookingB = bookingService.submit(memberBEmail, new BookingRequest(testResource.getId(), start, end));
        Booking bookingC = bookingService.submit(memberCEmail, new BookingRequest(testResource.getId(), start, end));

        bookingRepository.delete(bookingA);

        WaitlistOffer offerB = waitlistOfferService.processReleasedSlotForOffer(testResource.getId(), start, end).orElseThrow();
        UUID memberBId = memberRepository.findByEmail(memberBEmail).orElseThrow().getId();
        assertEquals(memberBId, offerB.getWaitlistEntry().getMemberId());

        // Simulate expiry by setting expiresAt in the past
        offerB.setExpiresAt(LocalDateTime.now().minusMinutes(1));
        offerRepository.save(offerB);

        // Process expired offers
        waitlistOfferService.processExpiredOffers();

        // B's offer should now be EXPIRED
        WaitlistOffer reloadedOfferB = offerRepository.findById(offerB.getId()).orElseThrow();
        assertEquals("EXPIRED", reloadedOfferB.getStatus());

        // C should now have an active offer!
        List<WaitlistOfferResponse> activeForC = waitlistOfferService.getActiveOffersForMember(memberCEmail);
        assertEquals(1, activeForC.size());
    }

    @Test
    @DisplayName("Accepting an offer creates a confirmation in-app notification")
    void testAcceptOfferCreatesConfirmationNotification() {
        LocalDateTime start = LocalDateTime.now().plusDays(10).withHour(10).withMinute(0).withSecond(0).withNano(0);
        LocalDateTime end = start.plusHours(2);

        Booking bookingA = bookingService.submit(memberAEmail, new BookingRequest(testResource.getId(), start, end));
        Booking bookingB = bookingService.submit(memberBEmail, new BookingRequest(testResource.getId(), start, end));

        bookingRepository.delete(bookingA);

        WaitlistOffer offerB = waitlistOfferService.processReleasedSlotForOffer(testResource.getId(), start, end).orElseThrow();
        waitlistOfferService.acceptOffer(memberBEmail, offerB.getId());

        List<InAppNotification> notifs = waitlistOfferService.getNotificationsForMember(memberBEmail);
        assertTrue(notifs.stream().anyMatch(n -> n.getTitle().contains("Booking Confirmed")));
    }
}
