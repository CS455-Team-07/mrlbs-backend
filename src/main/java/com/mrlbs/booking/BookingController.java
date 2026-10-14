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

    public BookingController(BookingService bookingService) {
        this(bookingService, null, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public BookingController(BookingService bookingService,
                             @org.springframework.beans.factory.annotation.Autowired(required = false)
                             com.mrlbs.waitlist.WaitlistService waitlistService,
                             @org.springframework.beans.factory.annotation.Autowired(required = false)
                             com.mrlbs.waitlist.WaitlistEntryRepository waitlistEntryRepository) {
        this.bookingService = bookingService;
        this.waitlistService = waitlistService;
        this.waitlistEntryRepository = waitlistEntryRepository;
    }

    public record ApprovalRequestResponse(UUID id, String memberEmail, String resourceName, String resourceType,
                                         LocalDateTime startTime, LocalDateTime endTime, BookingStatus status) {
        public static ApprovalRequestResponse from(Booking booking, String memberEmail) {
            String resourceName = booking.getResource() != null ? booking.getResource().getName() : "Archived Resource";
            String resourceType = booking.getResource() != null ? booking.getResource().getType() : "Unknown";
            return new ApprovalRequestResponse(
                    booking.getId(),
                    memberEmail,
                    resourceName,
                    resourceType,
                    booking.getStartTime(),
                    booking.getEndTime(),
                    booking.getStatus());
        }
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('MEMBER')")
    public BookingResponse submit(@AuthenticationPrincipal OidcUser user,
                                  @org.springframework.web.bind.annotation.RequestHeader(value = "X-User-Email", required = false) String headerEmail,
                                  @Valid @RequestBody BookingRequest request) {
        String email = user != null ? user.getEmail() : headerEmail;
        if (email == null || email.isBlank()) {
            throw new org.springframework.web.server.ResponseStatusException(
                    HttpStatus.UNAUTHORIZED, "Authentication required to submit booking");
        }
        Booking booking = bookingService.submit(email, request);
        boolean isWaitlist = booking.getStatus() == BookingStatus.IN_PROGRESS;
        Long queuePos = null;
        if (isWaitlist && waitlistService != null && waitlistEntryRepository != null) {
            var entryOpt = waitlistEntryRepository.findByBookingId(booking.getId());
            if (entryOpt.isPresent()) {
                queuePos = waitlistService.calculateQueuePosition(entryOpt.get());
            }
        }
        return BookingResponse.from(booking, isWaitlist, isWaitlist, queuePos);
    }

    @PostMapping("/draft")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('MEMBER')")
    public BookingResponse createDraft(@AuthenticationPrincipal OidcUser user,
                                       @org.springframework.web.bind.annotation.RequestHeader(value = "X-User-Email", required = false) String headerEmail,
                                       @Valid @RequestBody BookingRequest request) {
        String email = user != null ? user.getEmail() : headerEmail;
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
                                        @org.springframework.web.bind.annotation.RequestHeader(value = "X-User-Email", required = false) String headerEmail,
                                        @org.springframework.web.bind.annotation.PathVariable java.util.UUID id) {
        String email = user != null ? user.getEmail() : headerEmail;
        if (email == null || email.isBlank()) {
            throw new org.springframework.web.server.ResponseStatusException(
                    HttpStatus.UNAUTHORIZED, "Authentication required to confirm booking");
        }
        Booking confirmed = bookingService.confirmDraft(email, id);
        boolean isWaitlist = confirmed.getStatus() == BookingStatus.IN_PROGRESS;
        Long queuePos = null;
        if (isWaitlist && waitlistService != null && waitlistEntryRepository != null) {
            var entryOpt = waitlistEntryRepository.findByBookingId(confirmed.getId());
            if (entryOpt.isPresent()) {
                queuePos = waitlistService.calculateQueuePosition(entryOpt.get());
            }
        }
        BigDecimal memberBalance = bookingService.getMemberBalance(email);
        return BookingResponse.from(confirmed, isWaitlist, isWaitlist, queuePos, memberBalance);
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

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasRole('MEMBER')")
    public org.springframework.http.ResponseEntity<BookingResponse> cancelBooking(
            @AuthenticationPrincipal OidcUser user,
            @org.springframework.web.bind.annotation.RequestHeader(value = "X-User-Email", required = false) String headerEmail,
            @org.springframework.web.bind.annotation.PathVariable java.util.UUID id) {
        return deleteBooking(user, headerEmail, id);
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
        return bookingService.pendingApprovalsForAdmin(email, scope).stream()
                .map(booking -> ApprovalRequestResponse.from(booking, resolveMemberEmail(booking)))
                .toList();
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
