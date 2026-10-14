package com.mrlbs.booking;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/bookings")
public class BookingController {
    private final BookingService bookingService;
    private final com.mrlbs.ai.review.BookingAiReviewRepository reviewRepository;

    public BookingController(BookingService bookingService) {
        this(bookingService, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public BookingController(BookingService bookingService,
                             @org.springframework.beans.factory.annotation.Autowired(required = false) com.mrlbs.ai.review.BookingAiReviewRepository reviewRepository) {
        this.bookingService = bookingService;
        this.reviewRepository = reviewRepository;
    }

    public record ApprovalRequestResponse(UUID id, String memberEmail, String resourceName, String resourceType,
                                          LocalDateTime startTime, LocalDateTime endTime, BookingStatus status,
                                          com.mrlbs.ai.review.AiReviewSummaryResponse aiReview) {
        public static ApprovalRequestResponse from(Booking booking, String memberEmail, com.mrlbs.ai.review.AiReviewSummaryResponse aiReview) {
            String resourceName = booking.getResource() != null ? booking.getResource().getName() : "Archived Resource";
            String resourceType = booking.getResource() != null ? booking.getResource().getType() : "Unknown";
            return new ApprovalRequestResponse(
                    booking.getId(),
                    memberEmail,
                    resourceName,
                    resourceType,
                    booking.getStartTime(),
                    booking.getEndTime(),
                    booking.getStatus(),
                    aiReview);
        }

        public static ApprovalRequestResponse from(Booking booking, String memberEmail) {
            return from(booking, memberEmail, null);
        }
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('MEMBER')")
    public BookingResponse submit(@AuthenticationPrincipal OidcUser user,
                                  @Valid @RequestBody BookingRequest request) {
        return BookingResponse.from(bookingService.submit(user.getEmail(), request));
    }

    @PostMapping("/draft")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('MEMBER')")
    public BookingResponse createDraft(@AuthenticationPrincipal OidcUser user,
                                       @Valid @RequestBody BookingRequest request) {
        Booking draft = bookingService.createDraft(user.getEmail(), request);
        boolean clashes = bookingService.checkClash(draft);
        return BookingResponse.from(draft, clashes);
    }

    @PostMapping("/{id}/confirm")
    @PreAuthorize("hasRole('MEMBER')")
    public BookingResponse confirmDraft(@AuthenticationPrincipal OidcUser user,
                                        @org.springframework.web.bind.annotation.PathVariable java.util.UUID id) {
        Booking confirmed = bookingService.confirmDraft(user.getEmail(), id);
        return BookingResponse.from(confirmed, confirmed.getStatus() == BookingStatus.IN_PROGRESS);
    }

    @org.springframework.web.bind.annotation.DeleteMapping("/{id}")
    @PreAuthorize("hasRole('MEMBER')")
    public org.springframework.http.ResponseEntity<BookingResponse> deleteBooking(
            @AuthenticationPrincipal OidcUser user,
            @org.springframework.web.bind.annotation.RequestHeader(value = "X-User-Email", required = false) String headerEmail,
            @org.springframework.web.bind.annotation.PathVariable java.util.UUID id) {
        String email = user != null ? user.getEmail() : headerEmail;
        if (email == null || email.isBlank()) {
            throw new org.springframework.web.server.ResponseStatusException(
                    HttpStatus.UNAUTHORIZED, "Authentication required to cancel booking");
        }
        Booking cancelled = bookingService.deleteBooking(email, id);
        if (cancelled == null) {
            return org.springframework.http.ResponseEntity.noContent().build();
        }
        return org.springframework.http.ResponseEntity.ok(BookingResponse.from(cancelled));
    }




    @GetMapping
    @PreAuthorize("hasRole('MEMBER')")
    public List<BookingResponse> history(@AuthenticationPrincipal OidcUser user,
                                         @org.springframework.web.bind.annotation.RequestHeader(value = "X-User-Email", required = false) String headerEmail) {
        String email = user != null ? user.getEmail() : headerEmail;
        if (email == null || email.isBlank()) {
            throw new org.springframework.web.server.ResponseStatusException(
                    HttpStatus.UNAUTHORIZED, "Authentication required to view booking history");
        }
        return bookingService.history(email).stream().map(BookingResponse::from).toList();
    }

    @GetMapping("/approvals")
    @PreAuthorize("hasRole('ADMIN')")
    public List<ApprovalRequestResponse> approvals(@AuthenticationPrincipal OidcUser user) {
        String email = user.getEmail();
        String scope = com.mrlbs.security.AdminResourceScope.scopeForEmail(email).orElse(null);
        List<Booking> pending = bookingService.pendingApprovalsForAdmin(email, scope);

        List<UUID> bookingIds = pending.stream().map(Booking::getId).toList();
        java.util.Map<UUID, com.mrlbs.ai.review.BookingAiReview> reviewMap = (reviewRepository != null && !bookingIds.isEmpty())
                ? reviewRepository.findByBookingIdIn(bookingIds).stream()
                        .collect(java.util.stream.Collectors.toMap(com.mrlbs.ai.review.BookingAiReview::getBookingId, r -> r, (r1, r2) -> r1))
                : java.util.Map.of();

        return pending.stream()
                .map(booking -> ApprovalRequestResponse.from(
                        booking,
                        resolveMemberEmail(booking),
                        com.mrlbs.ai.review.AiReviewSummaryResponse.from(reviewMap.get(booking.getId()))
                ))
                .toList();
    }

    @GetMapping("/{id}/ai-review")
    @PreAuthorize("hasRole('ADMIN')")
    public org.springframework.http.ResponseEntity<com.mrlbs.ai.review.AiReviewSummaryResponse> getBookingAiReview(
            @org.springframework.web.bind.annotation.PathVariable UUID id) {
        if (reviewRepository == null) {
            return org.springframework.http.ResponseEntity.notFound().build();
        }
        return reviewRepository.findByBookingId(id)
                .map(com.mrlbs.ai.review.AiReviewSummaryResponse::from)
                .map(org.springframework.http.ResponseEntity::ok)
                .orElse(org.springframework.http.ResponseEntity.notFound().build());
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize("hasRole('ADMIN')")
    public BookingResponse approve(@AuthenticationPrincipal OidcUser user,
                                  @org.springframework.web.bind.annotation.PathVariable UUID id) {
        Booking approved = bookingService.reviewApprovalDecision(user.getEmail(), id, true);
        return BookingResponse.from(approved);
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize("hasRole('ADMIN')")
    public BookingResponse reject(@AuthenticationPrincipal OidcUser user,
                                 @org.springframework.web.bind.annotation.PathVariable UUID id) {
        Booking rejected = bookingService.reviewApprovalDecision(user.getEmail(), id, false);
        return BookingResponse.from(rejected);
    }

    private String resolveMemberEmail(Booking booking) {
        return bookingService.resolveMemberEmail(booking);
    }
}
