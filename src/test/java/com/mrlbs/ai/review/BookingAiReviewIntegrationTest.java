package com.mrlbs.ai.review;

import com.mrlbs.booking.Booking;
import com.mrlbs.booking.BookingController;
import com.mrlbs.booking.BookingRepository;
import com.mrlbs.booking.BookingStatus;
import com.mrlbs.resource.LabResource;
import com.mrlbs.resource.LabResourceRepository;
import org.junit.jupiter.api.Test;
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

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class BookingAiReviewIntegrationTest {

    @Autowired
    private BookingController bookingController;

    @Autowired
    private BookingRepository bookingRepository;

    @Autowired
    private LabResourceRepository resourceRepository;

    @Autowired
    private BookingAiReviewRepository reviewRepository;

    private OidcUser createAdminUser(String email) {
        OidcIdToken token = new OidcIdToken("token", Instant.now(), Instant.now().plusSeconds(3600), Map.of("sub", email, "email", email));
        return new DefaultOidcUser(Collections.emptyList(), token);
    }

    @Test
    void testApprovalsReturnsEnrichedAiReviewSummaries() {
        // 1. Setup resource
        LabResource resource = new LabResource(
                "H100 Cluster Node A",
                "GPU",
                "CC 201",
                8,
                List.of(),
                BigDecimal.ONE,
                new BigDecimal("3.00"),
                BigDecimal.valueOf(10)
        );
        resource = resourceRepository.save(resource);

        // 2. Setup pending booking 1 (has AI review)
        LocalDateTime start1 = LocalDateTime.now().plusDays(1).withHour(10).withMinute(0);
        LocalDateTime end1 = start1.plusHours(2);
        Booking booking1 = new Booking("member1@lab.edu", resource, start1, end1, BookingStatus.PENDING_APPROVAL, BigDecimal.valueOf(10));
        booking1.setMemberId(UUID.randomUUID());
        booking1 = bookingRepository.save(booking1);

        // Save AI Review for booking 1
        BookingAiReview review1 = new BookingAiReview(booking1.getId(), AiReviewStatus.COMPLETED);
        review1.setRecommendation(AiReviewRecommendation.RECOMMEND_APPROVAL);
        review1.setConfidenceScore(0.92);
        review1.setRationale("High historical hardware utilization (avg 85%) and 0 cancellations.");
        review1.setSupportingEvidence("Completed 3 prior bookings with 100% telemetry coverage.");
        review1.setDataLimitations("Device-level metrics without per-user process attribution.");
        review1.setDemonstrationData(true);
        review1.setModelName("gemini-3.5-flash-lite");
        review1.setCompletedAt(LocalDateTime.now());
        reviewRepository.save(review1);

        // 3. Setup pending booking 2 (AI review still in progress / pending)
        LocalDateTime start2 = LocalDateTime.now().plusDays(2).withHour(14).withMinute(0);
        LocalDateTime end2 = start2.plusHours(2);
        Booking booking2 = new Booking("member2@lab.edu", resource, start2, end2, BookingStatus.PENDING_APPROVAL, BigDecimal.valueOf(10));
        booking2.setMemberId(UUID.randomUUID());
        booking2 = bookingRepository.save(booking2);

        // 4. Query approvals endpoint as GPU admin
        OidcUser adminUser = createAdminUser("admin@lab.edu");
        var auth = new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                adminUser, null, List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ADMIN")));
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(auth);

        List<BookingController.ApprovalRequestResponse> approvals = bookingController.approvals(adminUser);

        assertNotNull(approvals);
        assertTrue(approvals.size() >= 2);

        final UUID id1 = booking1.getId();
        final UUID id2 = booking2.getId();

        // Verify booking 1 has complete AI review summary
        var response1 = approvals.stream()
                .filter(a -> a.id().equals(id1))
                .findFirst()
                .orElse(null);
        assertNotNull(response1);
        assertNotNull(response1.aiReview());
        assertEquals(AiReviewRecommendation.RECOMMEND_APPROVAL, response1.aiReview().recommendation());
        assertEquals(0.92, response1.aiReview().confidenceScore());
        assertEquals(AiReviewStatus.COMPLETED, response1.aiReview().status());
        assertTrue(response1.aiReview().isDemonstrationData());
        assertEquals("High historical hardware utilization (avg 85%) and 0 cancellations.", response1.aiReview().rationale());

        // Verify booking 2 has null AI review (evaluation pending/not completed yet)
        var response2 = approvals.stream()
                .filter(a -> a.id().equals(id2))
                .findFirst()
                .orElse(null);
        assertNotNull(response2);
        assertNull(response2.aiReview());

        // 5. Test standalone GET /api/bookings/{id}/ai-review endpoint
        var singleReview = bookingController.getBookingAiReview(adminUser, id1);
        assertEquals(200, singleReview.getStatusCode().value());
        assertNotNull(singleReview.getBody());
        assertEquals(AiReviewRecommendation.RECOMMEND_APPROVAL, singleReview.getBody().recommendation());

        var notFoundReview = bookingController.getBookingAiReview(adminUser, UUID.randomUUID());
        assertEquals(404, notFoundReview.getStatusCode().value());
    }
}
