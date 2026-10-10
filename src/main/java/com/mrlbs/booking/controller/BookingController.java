package com.mrlbs.booking.controller;

import com.mrlbs.booking.Booking;
import com.mrlbs.booking.service.BookingService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDateTime;
import java.util.Map;

@RestController
@RequestMapping("/api/bookings")
public class BookingController {
    private final BookingService bookingService;

    public BookingController(BookingService bookingService) {
        this.bookingService = bookingService;
    }

    /**
     * Create a new draft booking. The memberId is retrieved from the security context or a header.
     */
    @PostMapping("/draft")
    public ResponseEntity<Booking> createDraft(@RequestBody DraftRequest request,
                                                @RequestHeader(value = "X-Member-Id", required = false) Long headerMemberId) {
        Long memberId = resolveMemberId(headerMemberId);
        Booking booking = bookingService.createDraft(memberId, request.getResourceId(), request.getStartTime(), request.getEndTime());
        return ResponseEntity.ok(booking);
    }

    /**
     * Calculate and return the credit cost for a draft booking.
     */
    @GetMapping("/{id}/price")
    public ResponseEntity<Map<String, Object>> getPrice(@PathVariable Long id,
                                                         @RequestHeader(value = "X-Member-Id", required = false) Long headerMemberId) {
        Long memberId = resolveMemberId(headerMemberId);
        Booking booking = bookingService.calculateCredits(id, memberId);
        return ResponseEntity.ok(Map.of("bookingId", booking.getId(), "creditCost", booking.getCreditCost()));
    }

    private Long resolveMemberId(Long headerMemberId) {
        // Placeholder: In a real system, extract from SecurityContextHolder.
        if (headerMemberId != null) {
            return headerMemberId;
        }
        // Fallback for now – throw to indicate missing identity.
        throw new IllegalStateException("Member identity not provided");
    }

    // DTO for draft creation request
    public static class DraftRequest {
        private Long resourceId;
        private LocalDateTime startTime;
        private LocalDateTime endTime;
        public Long getResourceId() { return resourceId; }
        public void setResourceId(Long resourceId) { this.resourceId = resourceId; }
        public LocalDateTime getStartTime() { return startTime; }
        public void setStartTime(LocalDateTime startTime) { this.startTime = startTime; }
        public LocalDateTime getEndTime() { return endTime; }
        public void setEndTime(LocalDateTime endTime) { this.endTime = endTime; }
    }
}
