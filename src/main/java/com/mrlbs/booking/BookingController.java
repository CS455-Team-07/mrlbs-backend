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

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/bookings")
public class BookingController {
    private final BookingService bookingService;
    private final com.mrlbs.waitlist.WaitlistService waitlistService;
    private final com.mrlbs.waitlist.WaitlistEntryRepository waitlistEntryRepository;
    private final com.mrlbs.ai.review.BookingAiReviewRepository reviewRepository;

   public BookingController(BookingService bookingService) {
        this(bookingService, null, null, null);
    }

    public BookingController(BookingService bookingService,
                             com.mrlbs.ai.review.BookingAiReviewRepository reviewRepository) {
        this(bookingService, null, null, reviewRepository);
    }

    public BookingController(BookingService bookingService,
                             com.mrlbs.waitlist.WaitlistService waitlistService,
                             com.mrlbs.waitlist.WaitlistEntryRepository waitlistEntryRepository) {
        this(bookingService, waitlistService, waitlistEntryRepository, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public BookingController(BookingService bookingService,
                             @org.springframework.beans.factory.annotation.Autowired(required = false)
                             com.mrlbs.waitlist.WaitlistService waitlistService,
                             @org.springframework.beans.factory.annotation.Autowired(required = false)
                             com.mrlbs.waitlist.WaitlistEntryRepository waitlistEntryRepository,
                             @org.springframework.beans.factory.annotation.Autowired(required = false)
                             com.mrlbs.ai.review.BookingAiReviewRepository reviewRepository) {
        this.bookingService = bookingService;
        this.waitlistService = waitlistService;
        this.waitlistEntryRepository = waitlistEntryRepository;
        this.reviewRepository = reviewRepository;
    }

    public record ApprovalRequestResponse(UUID id, String memberEmail, String resourceName, String resourceType,
                                          LocalDateTime startTime, LocalDateTime endTime, LocalDateTime createdAt,
                                          BookingStatus status,
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
                    booking.getCreatedAt(),
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
        String email = user == null ? null : user.getEmail();
        if (email == null || email.isBlank()) {
            throw new org.springframework.web.server.ResponseStatusException(
                    HttpStatus.UNAUTHORIZED, "Authentication required to submit booking");
        }
        Booking booking = bookingService.submit(email, request);
        boolean isWaitlist = booking.getStatus() == BookingStatus.IN_PROGRESS
                || (waitlistEntryRepository != null && waitlistEntryRepository.findByBookingId(booking.getId()).isPresent());
        boolean clashes = isWaitlist || bookingService.checkClash(booking);
        Long queuePos = null;
        if (isWaitlist && waitlistService != null && waitlistEntryRepository != null) {
            var entryOpt = waitlistEntryRepository.findByBookingId(booking.getId());
            if (entryOpt.isPresent()) {
                queuePos = waitlistService.calculateQueuePosition(entryOpt.get());
            }
        } else if (clashes && waitlistService != null && booking.getResource() != null) {
            queuePos = waitlistService.getNextQueuePosition(booking.getResource().getId(), booking.getStartTime(), booking.getEndTime());
        }
        return BookingResponse.from(booking, clashes, isWaitlist, queuePos);
    }

    @PostMapping("/draft")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('MEMBER')")
    public BookingResponse createDraft(@AuthenticationPrincipal OidcUser user,
                                       @Valid @RequestBody BookingRequest request) {
        String email = user == null ? null : user.getEmail();
        if (email == null || email.isBlank()) {
            throw new org.springframework.web.server.ResponseStatusException(
                    HttpStatus.UNAUTHORIZED, "Authentication required to create draft");
        }
        Booking draft = bookingService.createDraft(email, request);
        boolean clashes = bookingService.checkClash(draft);
        Long nextQueuePos = null;
        if (clashes && waitlistService != null && draft.getResource() != null) {
            nextQueuePos = waitlistService.getNextQueuePosition(draft.getResource().getId(), draft.getStartTime(), draft.getEndTime());
        }
        BigDecimal memberBalance = bookingService.getMemberBalance(email);
        return BookingResponse.from(draft, clashes, clashes, nextQueuePos, memberBalance);
    }

    @PostMapping("/{id}/confirm")
    @PreAuthorize("hasRole('MEMBER')")
    public BookingResponse confirmDraft(@AuthenticationPrincipal OidcUser user,
                                        @org.springframework.web.bind.annotation.PathVariable java.util.UUID id) {
        String email = user == null ? null : user.getEmail();
        if (email == null || email.isBlank()) {
            throw new org.springframework.web.server.ResponseStatusException(
                    HttpStatus.UNAUTHORIZED, "Authentication required to confirm booking");
        }
        Booking confirmed = bookingService.confirmDraft(email, id);
        boolean isWaitlist = confirmed.getStatus() == BookingStatus.IN_PROGRESS
                || (waitlistEntryRepository != null && waitlistEntryRepository.findByBookingId(confirmed.getId()).isPresent());
        boolean clashes = isWaitlist || bookingService.checkClash(confirmed);
        Long queuePos = null;
        if (isWaitlist && waitlistService != null && waitlistEntryRepository != null) {
            var entryOpt = waitlistEntryRepository.findByBookingId(confirmed.getId());
            if (entryOpt.isPresent()) {
                queuePos = waitlistService.calculateQueuePosition(entryOpt.get());
            }
        } else if (clashes && waitlistService != null && confirmed.getResource() != null) {
            queuePos = waitlistService.getNextQueuePosition(confirmed.getResource().getId(), confirmed.getStartTime(), confirmed.getEndTime());
        }
        BigDecimal memberBalance = bookingService.getMemberBalance(email);
        return BookingResponse.from(confirmed, clashes, isWaitlist, queuePos, memberBalance);
    }

    @org.springframework.web.bind.annotation.DeleteMapping("/{id}")
    @PreAuthorize("hasRole('MEMBER')")
    public org.springframework.http.ResponseEntity<BookingResponse> deleteBooking(
            @AuthenticationPrincipal OidcUser user,
            @org.springframework.web.bind.annotation.PathVariable java.util.UUID id) {
        String email = user == null ? null : user.getEmail();
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

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasRole('MEMBER')")
    public org.springframework.http.ResponseEntity<BookingResponse> cancelBooking(
            @AuthenticationPrincipal OidcUser user,
            @org.springframework.web.bind.annotation.PathVariable java.util.UUID id) {
        return deleteBooking(user, id);
    }




    @GetMapping
    @PreAuthorize("hasRole('MEMBER')")
    public List<BookingResponse> history(@AuthenticationPrincipal OidcUser user) {
        String email = user == null ? null : user.getEmail();
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
            @AuthenticationPrincipal OidcUser user,
            @org.springframework.web.bind.annotation.PathVariable UUID id) {
        if (reviewRepository == null || user == null || !bookingService.canReadAiReview(user.getEmail(), id)) {
            return org.springframework.http.ResponseEntity.notFound().build();
        }
        return reviewRepository.findByBookingId(id)
                .map(com.mrlbs.ai.review.AiReviewSummaryResponse::from)
                .map(org.springframework.http.ResponseEntity::ok)
                .orElse(org.springframework.http.ResponseEntity.notFound().build());
    }

    public record RejectionRequest(String reason) {}

    @PostMapping("/{id}/approve")
    @PreAuthorize("hasRole('ADMIN')")
    public BookingResponse approve(@AuthenticationPrincipal OidcUser user,
                                  @org.springframework.web.bind.annotation.PathVariable UUID id) {
        Booking approved = bookingService.reviewApprovalDecision(user.getEmail(), id, true, null);
        return BookingResponse.from(approved);
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize("hasRole('ADMIN')")
    public BookingResponse reject(@AuthenticationPrincipal OidcUser user,
                                 @org.springframework.web.bind.annotation.PathVariable UUID id,
                                 @RequestBody(required = false) RejectionRequest request) {
        String reason = (request != null && request.reason() != null && !request.reason().isBlank())
                ? request.reason()
                : "Rejected by administrator";
        Booking rejected = bookingService.reviewApprovalDecision(user.getEmail(), id, false, reason);
        return BookingResponse.from(rejected);
    }

    public BookingResponse reject(OidcUser user, UUID id) {
        return reject(user, id, null);
    }

    private String resolveMemberEmail(Booking booking) {
        return bookingService.resolveMemberEmail(booking);
    }
}
