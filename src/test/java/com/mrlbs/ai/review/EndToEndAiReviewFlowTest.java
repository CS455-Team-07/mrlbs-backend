package com.mrlbs.ai.review;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mrlbs.booking.Booking;
import com.mrlbs.booking.BookingController;
import com.mrlbs.booking.BookingRepository;
import com.mrlbs.booking.BookingRequest;
import com.mrlbs.booking.BookingService;
import com.mrlbs.booking.BookingStatus;
import com.mrlbs.booking.credit.CreditAccount;
import com.mrlbs.booking.credit.CreditAccountRepository;
import com.mrlbs.resource.BookingPolicy;
import com.mrlbs.resource.BookingRule;
import com.mrlbs.resource.LabResource;
import com.mrlbs.resource.LabResourceRepository;
import com.mrlbs.telemetry.analyzer.RequesterHistoryAnalyzer;
import com.mrlbs.telemetry.analyzer.RequesterHistoryProfile;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class EndToEndAiReviewFlowTest {

    @Autowired
    private BookingService bookingService;

    @Autowired
    private BookingController bookingController;

    @Autowired
    private BookingRepository bookingRepository;

    @Autowired
    private LabResourceRepository resourceRepository;

    @Autowired
    private CreditAccountRepository creditAccountRepository;

    @Autowired
    private BookingAiReviewRepository reviewRepository;

    @Autowired
    private BookingOutboxRepository outboxRepository;

    @Autowired
    private ObjectMapper objectMapper;

    private OidcUser createAdminUser(String email) {
        OidcIdToken token = new OidcIdToken("token", Instant.now(), Instant.now().plusSeconds(3600), Map.of("sub", email, "email", email));
        return new DefaultOidcUser(Collections.emptyList(), token);
    }

    @Test
    void testCompleteLifecycleFromSubmissionToAdminApproval() throws Exception {
        String memberEmail = "researcher@lab.edu";
        String adminEmail = "admin@lab.edu";

        // Setup Security Context for Admin
        OidcUser adminUser = createAdminUser(adminEmail);
        var auth = new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                adminUser, null, List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ADMIN")));
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(auth);

        // 1. Setup Resource with APPROVAL_REQUIRED policy
        LabResource resource = new LabResource(
                "NVIDIA DGX H100 Node 1",
                "GPU",
                "CC 201",
                8,
                List.of("Strictly for approved deep learning jobs"),
                BigDecimal.valueOf(1.0),
                BigDecimal.valueOf(3.0),
                BigDecimal.valueOf(50.0)
        );
        BookingRule rule = new BookingRule();
        rule.setBookingPolicy(BookingPolicy.APPROVAL_REQUIRED);
        rule.setMinDurationMinutes(60);
        rule.setMaxDurationMinutes(480);
        rule.setBufferTimeMinutes(15);
        rule.setMaxBookingsPerMember(5);
        resource.setBookingRule(rule);
        resource = resourceRepository.save(resource);

        // 2. Setup member credit account
        CreditAccount account = new CreditAccount(memberEmail, BigDecimal.valueOf(1000.0));
        creditAccountRepository.save(account);

        // 3. Step 1: Member submits booking request
        LocalDateTime start = LocalDateTime.now().plusDays(1).withHour(10).withMinute(0);
        LocalDateTime end = start.plusHours(2);
        BookingRequest request = new BookingRequest(resource.getId(), start, end);

        Booking booking = bookingService.submit(memberEmail, request);
        assertNotNull(booking);
        assertEquals(BookingStatus.PENDING_APPROVAL, booking.getStatus(), "Booking must be PENDING_APPROVAL");

        // Verify credit deduction
        CreditAccount updatedAccount = creditAccountRepository.findForUpdate(memberEmail).orElseThrow();
        assertTrue(updatedAccount.getBalance().compareTo(BigDecimal.valueOf(1000.0)) < 0, "Credits must be debited");

        // 4. Step 2: Verify Transactional Outbox Event & Placeholder Review
        List<BookingOutboxEvent> outboxEvents = outboxRepository.findByBookingId(booking.getId());
        assertFalse(outboxEvents.isEmpty(), "Outbox event must be staged for pending approval booking");
        BookingOutboxEvent outboxEvent = outboxEvents.get(0);
        assertEquals("BOOKING_PENDING_APPROVAL", outboxEvent.getEventType());

        BookingAiReview placeholderReview = reviewRepository.findByBookingId(booking.getId()).orElse(null);
        assertNotNull(placeholderReview, "Initial placeholder review must be staged");
        assertTrue(placeholderReview.getStatus() == AiReviewStatus.PENDING ||
                   placeholderReview.getStatus() == AiReviewStatus.FALLBACK_HUMAN_ONLY,
                "Initial review must be PENDING or FALLBACK_HUMAN_ONLY when broker is offline");

        // 5. Step 3: Consumer receives event and completes AI advisory review
        RequesterHistoryAnalyzer historyAnalyzer = mock(RequesterHistoryAnalyzer.class);
        AiReviewAgentService agentService = mock(AiReviewAgentService.class);
        Channel channel = mock(Channel.class);
        BookingAiReviewConsumer consumer = new BookingAiReviewConsumer(historyAnalyzer, agentService, reviewRepository, objectMapper);

        RequesterHistoryProfile profile = mock(RequesterHistoryProfile.class);
        when(historyAnalyzer.analyzeRequesterHistory(any(), eq(resource.getId()), eq(booking.getId())))
                .thenReturn(profile);

        AiReviewAgentService.ReviewEvaluationResult reviewResult = new AiReviewAgentService.ReviewEvaluationResult(
                AiReviewRecommendation.RECOMMEND_APPROVAL,
                0.94,
                "Strong past utilization (avg 88.5%), clean track record with 0 cancellations.",
                "Completed 4 prior bookings on DGX H100 with consistent telemetry.",
                "Node-level metrics without per-user process attribution.",
                true,
                "gemini-3.5-flash-lite"
        );
        when(agentService.evaluateBooking(any(), eq(profile))).thenReturn(reviewResult);

        // Execute consumer message handling
        consumer.processBookingReview(outboxEvent.getPayload(), channel, 101L);

        // Verify consumer acknowledged message
        verify(channel).basicAck(eq(101L), eq(false));

        // Verify review record updated to COMPLETED
        BookingAiReview completedReview = reviewRepository.findByBookingId(booking.getId()).orElseThrow();
        assertEquals(AiReviewStatus.COMPLETED, completedReview.getStatus());
        assertEquals(AiReviewRecommendation.RECOMMEND_APPROVAL, completedReview.getRecommendation());
        assertEquals(0.94, completedReview.getConfidenceScore());

        // 6. Step 4: Admin views Pending Approvals on Dashboard
        List<BookingController.ApprovalRequestResponse> approvals = bookingController.approvals(adminUser);
        assertFalse(approvals.isEmpty());

        var approvalCard = approvals.stream()
                .filter(a -> a.id().equals(booking.getId()))
                .findFirst()
                .orElse(null);
        assertNotNull(approvalCard, "Booking must be listed in Admin approval queue");
        assertNotNull(approvalCard.aiReview(), "Approval request card must be enriched with AI review");
        assertEquals(AiReviewRecommendation.RECOMMEND_APPROVAL, approvalCard.aiReview().recommendation());
        assertEquals(0.94, approvalCard.aiReview().confidenceScore());
        assertEquals(AiReviewStatus.COMPLETED, approvalCard.aiReview().status());
        assertTrue(approvalCard.aiReview().rationale().contains("Strong past utilization"));

        // 7. Step 5: Human Admin exercises final authority to APPROVE
        var approvalResponse = bookingController.approve(adminUser, booking.getId());
        assertNotNull(approvalResponse);

        Booking confirmedBooking = bookingRepository.findById(booking.getId()).orElseThrow();
        assertEquals(BookingStatus.CONFIRMED, confirmedBooking.getStatus(),
                "Booking status must transition to CONFIRMED only after Admin approval");

        // Verify AI review remains persisted with completed audit details
        BookingAiReview finalReview = reviewRepository.findByBookingId(booking.getId()).orElseThrow();
        assertEquals(AiReviewStatus.COMPLETED, finalReview.getStatus());
        assertNotNull(finalReview.getCompletedAt());
    }

    @Test
    void testAdminRejectionFlowRefundsCredits() throws Exception {
        String memberEmail = "student@lab.edu";
        String adminEmail = "admin@lab.edu";

        OidcUser adminUser = createAdminUser(adminEmail);
        var auth = new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                adminUser, null, List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ADMIN")));
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(auth);

        // Setup Resource
        LabResource resource = new LabResource(
                "NVIDIA DGX H100 Node 2",
                "GPU",
                "CC 202",
                8,
                List.of(),
                BigDecimal.ONE,
                BigDecimal.valueOf(3.0),
                BigDecimal.valueOf(50.0)
        );
        BookingRule rule = new BookingRule();
        rule.setBookingPolicy(BookingPolicy.APPROVAL_REQUIRED);
        rule.setMinDurationMinutes(60);
        rule.setMaxDurationMinutes(480);
        rule.setBufferTimeMinutes(15);
        rule.setMaxBookingsPerMember(5);
        resource.setBookingRule(rule);
        resource = resourceRepository.save(resource);

        // Setup credits
        BigDecimal initialBalance = BigDecimal.valueOf(500.0);
        CreditAccount account = new CreditAccount(memberEmail, initialBalance);
        creditAccountRepository.save(account);

        // Submit booking
        LocalDateTime start = LocalDateTime.now().plusDays(1).withHour(14).withMinute(0);
        LocalDateTime end = start.plusHours(2);
        Booking booking = bookingService.submit(memberEmail, new BookingRequest(resource.getId(), start, end));
        assertEquals(BookingStatus.PENDING_APPROVAL, booking.getStatus());

        // Admin decides to REJECT
        var rejectionResponse = bookingController.reject(adminUser, booking.getId());
        assertNotNull(rejectionResponse);

        Booking rejectedBooking = bookingRepository.findById(booking.getId()).orElseThrow();
        assertEquals(BookingStatus.REJECTED, rejectedBooking.getStatus(), "Booking status must be REJECTED");

        // Verify credits were refunded to member
        CreditAccount refundedAccount = creditAccountRepository.findForUpdate(memberEmail).orElseThrow();
        assertEquals(0, initialBalance.compareTo(refundedAccount.getBalance()),
                "Debited credits must be fully refunded upon admin rejection");
    }
}
