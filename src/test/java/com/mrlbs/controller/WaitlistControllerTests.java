package com.mrlbs.controller;

import com.mrlbs.booking.Booking;
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
import com.mrlbs.waitlist.WaitlistEntry;
import com.mrlbs.waitlist.WaitlistEntryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class WaitlistControllerTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private BookingService bookingService;

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

    private LabResource testResource;
    private final String memberEmail = "member@iitk.ac.in";
    private final String otherEmail = "other@iitk.ac.in";

    @BeforeEach
    void setup() {
        notificationRepository.deleteAll();
        offerRepository.deleteAll();
        waitlistEntryRepository.deleteAll();
        bookingRepository.deleteAll();

        LabResource resource = new LabResource("Test Lab", "Lab", "Bldg 1", 1, List.of("Rule 1"),
                new BigDecimal("1.00"), new BigDecimal("3.00"), new BigDecimal("10.00"));
        resource.getBookingRule().setBookingPolicy(BookingPolicy.OPEN);
        resource.getBookingRule().setMinDurationMinutes(30);
        resource.getBookingRule().setMaxDurationMinutes(180);
        resource.getBookingRule().setBufferTimeMinutes(0);
        resource.getBookingRule().setMaxBookingsPerMember(5);
        testResource = resourceRepository.save(resource);

        memberRepository.findByEmail(memberEmail).orElseGet(() ->
                memberRepository.save(new Member(memberEmail, "Test Member", "MEMBER")));
        creditAccountRepository.findById(memberEmail).ifPresentOrElse(
                acc -> {
                    acc.credit(new BigDecimal("1000").subtract(acc.getBalance()));
                    creditAccountRepository.save(acc);
                },
                () -> creditAccountRepository.save(new CreditAccount(memberEmail, new BigDecimal("1000")))
        );

        memberRepository.findByEmail(otherEmail).orElseGet(() ->
                memberRepository.save(new Member(otherEmail, "Other Member", "MEMBER")));
        creditAccountRepository.findById(otherEmail).ifPresentOrElse(
                acc -> {
                    acc.credit(new BigDecimal("1000").subtract(acc.getBalance()));
                    creditAccountRepository.save(acc);
                },
                () -> creditAccountRepository.save(new CreditAccount(otherEmail, new BigDecimal("1000")))
        );
    }

    @Test
    void getMyWaitlistReturnsActiveEntriesWithQueuePosition() throws Exception {
        LocalDateTime start = LocalDateTime.now().plusDays(5).withHour(10).withMinute(0).withSecond(0).withNano(0);
        LocalDateTime end = start.plusHours(1);

        // other occupies slot
        bookingService.submit(otherEmail, new BookingRequest(testResource.getId(), start, end));
        // member joins waitlist
        bookingService.submit(memberEmail, new BookingRequest(testResource.getId(), start, end));

        mockMvc.perform(get("/api/waitlist")
                        .with(SecurityMockMvcRequestPostProcessors.oidcLogin().idToken(token -> token.claim("email", memberEmail)).authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_MEMBER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].status", is("IN_PROGRESS")))
                .andExpect(jsonPath("$[0].queuePosition", is(1)))
                .andExpect(jsonPath("$[0].resourceName", is("Test Lab")));
    }

    @Test
    void leaveWaitlistSuccessfullyUpdatesStatusToLeft() throws Exception {
        LocalDateTime start = LocalDateTime.now().plusDays(5).withHour(12).withMinute(0).withSecond(0).withNano(0);
        LocalDateTime end = start.plusHours(1);

        bookingService.submit(otherEmail, new BookingRequest(testResource.getId(), start, end));
        Booking booking = bookingService.submit(memberEmail, new BookingRequest(testResource.getId(), start, end));

        WaitlistEntry entry = waitlistEntryRepository.findByBookingId(booking.getId()).orElseThrow();

        mockMvc.perform(delete("/api/waitlist/" + entry.getId())
                        .with(SecurityMockMvcRequestPostProcessors.oidcLogin().idToken(token -> token.claim("email", memberEmail)).authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_MEMBER")))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("CANCELLED")));
    }

    @Autowired
    private com.mrlbs.waitlist.WaitlistOfferService waitlistOfferService;

    @Autowired
    private com.mrlbs.waitlist.WaitlistOfferRepository offerRepository;

    @Autowired
    private com.mrlbs.notification.InAppNotificationRepository notificationRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @ParameterizedTest
    @CsvSource({
            "approve, false, CONFIRMED, Booking Request Approved, approved and confirmed",
            "approve, true, IN_PROGRESS, Booking Request Approved, approved and placed on the waitlist",
            "reject, false, REJECTED, Booking Request Rejected, was rejected"
    })
    void memberReceivesTheCommittedDecisionImmediately(String action, boolean occupied, BookingStatus expectedStatus,
                                                       String title, String decision) throws Exception {
        Booking pending = pendingDecision(occupied);
        var request = post("/api/bookings/" + pending.getId() + "/" + action)
                .with(oidcLogin().idToken(token -> token.claim("email", "admin@iitk.ac.in"))
                        .authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                .with(csrf());
        String reason = "x".repeat(1000);
        if (expectedStatus == BookingStatus.REJECTED) {
            request.contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"" + reason + "\"}");
        }
        LocalDateTime beforeDecision = LocalDateTime.now();
        mockMvc.perform(request)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is(expectedStatus.name())));
        LocalDateTime afterDecision = LocalDateTime.now();
        Booking recorded = bookingRepository.findById(pending.getId()).orElseThrow();
        assertThat(recorded.getStatus()).isEqualTo(expectedStatus);
        if (expectedStatus == BookingStatus.REJECTED) {
            assertThat(recorded.getRejectionReason()).isEqualTo(reason);
        }

        mockMvc.perform(get("/api/waitlist/notifications")
                        .with(oidcLogin().idToken(token -> token.claim("email", memberEmail))
                                .authorities(new SimpleGrantedAuthority("ROLE_MEMBER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].title", is(title)))
                .andExpect(jsonPath("$[0].message", allOf(containsString(pending.getId().toString()),
                        containsString(testResource.getName()), containsString(decision))))
                .andExpect(jsonPath("$[0].isRead", is(false)));

        var notification = notificationRepository.findByMemberIdOrderByCreatedAtDesc(pending.getMemberId()).getFirst();
        assertThat(notification.getCreatedAt()).isBetween(beforeDecision.minusNanos(1_000_000), afterDecision.plusNanos(1_000_000));
        assertThat(java.time.Duration.between(beforeDecision, LocalDateTime.now())).isLessThan(java.time.Duration.ofSeconds(60));
        var otherMemberId = memberRepository.findByEmail(otherEmail).orElseThrow().getId();
        assertThat(notificationRepository.findByMemberIdOrderByCreatedAtDesc(otherMemberId)).isEmpty();
    }

    @Test
    void rolledBackDecisionDoesNotDeliverNotification() {
        Booking pending = pendingDecision(false);
        new TransactionTemplate(transactionManager).executeWithoutResult(transaction -> {
            bookingService.reviewApprovalDecision("admin@iitk.ac.in", pending.getId(), false, "Needs maintenance.");
            transaction.setRollbackOnly();
        });
        assertThat(bookingRepository.findById(pending.getId()).orElseThrow().getStatus())
                .isEqualTo(BookingStatus.PENDING_APPROVAL);
        assertThat(notificationRepository.findByMemberIdOrderByCreatedAtDesc(pending.getMemberId())).isEmpty();
    }

    @Test
    void competingDecisionsDeliverOneNotification() throws Exception {
        Booking pending = pendingDecision(false);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<Boolean> reject = () -> {
                ready.countDown();
                if (!start.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Review workers did not start");
                }
                try {
                    bookingService.reviewApprovalDecision("admin@iitk.ac.in", pending.getId(), false, "Needs maintenance.");
                    return true;
                } catch (IllegalStateException alreadyDecided) {
                    assertThat(alreadyDecided).hasMessage("Booking is not awaiting approval.");
                    return false;
                }
            };
            var first = executor.submit(reject);
            var second = executor.submit(reject);
            boolean bothReady = ready.await(5, TimeUnit.SECONDS);
            start.countDown();
            assertThat(bothReady).isTrue();
            assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        }
        assertThat(notificationRepository.findByMemberIdOrderByCreatedAtDesc(pending.getMemberId())).hasSize(1);
    }

    private Booking pendingDecision(boolean occupied) {
        LocalDateTime start = LocalDateTime.now().plusDays(5).withHour(10).withMinute(0).withSecond(0).withNano(0);
        if (occupied) {
            bookingService.submit(otherEmail, new BookingRequest(testResource.getId(), start, start.plusHours(1)));
        }
        testResource.getBookingRule().setBookingPolicy(BookingPolicy.APPROVAL_REQUIRED);
        resourceRepository.save(testResource);
        Booking pending = new Booking(memberEmail, testResource, start, start.plusHours(1),
                BookingStatus.PENDING_APPROVAL, BigDecimal.ZERO);
        pending.setMemberId(memberRepository.findByEmail(memberEmail).orElseThrow().getId());
        return bookingRepository.save(pending);
    }

    @Test
    void unauthenticatedRequestIsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/waitlist"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void getActiveOffersAndAcceptOfferEndpoint() throws Exception {
        notificationRepository.deleteAll();
        offerRepository.deleteAll();

        LocalDateTime start = LocalDateTime.now().plusDays(5).withHour(14).withMinute(0).withSecond(0).withNano(0);
        LocalDateTime end = start.plusHours(1);

        Booking bookingA = bookingService.submit(otherEmail, new BookingRequest(testResource.getId(), start, end));
        bookingService.submit(memberEmail, new BookingRequest(testResource.getId(), start, end));

        bookingRepository.delete(bookingA);

        // Released slot triggers offer
        var offer = waitlistOfferService.processReleasedSlotForOffer(testResource.getId(), start, end).orElseThrow();

        // 1. GET /api/waitlist/offers
        mockMvc.perform(get("/api/waitlist/offers")
                        .with(SecurityMockMvcRequestPostProcessors.oidcLogin().idToken(token -> token.claim("email", memberEmail)).authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_MEMBER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id", is(offer.getId().toString())))
                .andExpect(jsonPath("$[0].status", is("PENDING")));

        // 2. GET /api/waitlist/notifications
        mockMvc.perform(get("/api/waitlist/notifications")
                        .with(SecurityMockMvcRequestPostProcessors.oidcLogin().idToken(token -> token.claim("email", memberEmail)).authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_MEMBER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].isRead", is(false)));

        // 3. POST /api/waitlist/offers/{id}/accept
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/waitlist/offers/" + offer.getId() + "/accept")
                        .with(SecurityMockMvcRequestPostProcessors.oidcLogin().idToken(token -> token.claim("email", memberEmail)).authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_MEMBER")))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("CONFIRMED")));
    }

    @Test
    void declineOfferEndpoint() throws Exception {
        notificationRepository.deleteAll();
        offerRepository.deleteAll();

        LocalDateTime start = LocalDateTime.now().plusDays(6).withHour(10).withMinute(0).withSecond(0).withNano(0);
        LocalDateTime end = start.plusHours(1);

        Booking bookingA = bookingService.submit(otherEmail, new BookingRequest(testResource.getId(), start, end));
        bookingService.submit(memberEmail, new BookingRequest(testResource.getId(), start, end));

        bookingRepository.delete(bookingA);

        var offer = waitlistOfferService.processReleasedSlotForOffer(testResource.getId(), start, end).orElseThrow();

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/waitlist/offers/" + offer.getId() + "/decline")
                        .with(SecurityMockMvcRequestPostProcessors.oidcLogin().idToken(token -> token.claim("email", memberEmail)).authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_MEMBER")))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("DECLINED")));
    }
}
