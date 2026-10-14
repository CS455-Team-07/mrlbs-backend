package com.mrlbs.booking;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/bookings")
public class BookingController {
    private final BookingService bookingService;

    public BookingController(BookingService bookingService) {
        this.bookingService = bookingService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public BookingResponse submit(@AuthenticationPrincipal OidcUser user,
                                  @Valid @RequestBody BookingRequest request) {
        return BookingResponse.from(bookingService.submit(user.getEmail(), request));
    }

    @PostMapping("/draft")
    @ResponseStatus(HttpStatus.CREATED)
    public BookingResponse createDraft(@AuthenticationPrincipal OidcUser user,
                                       @Valid @RequestBody BookingRequest request) {
        Booking draft = bookingService.createDraft(user.getEmail(), request);
        boolean clashes = bookingService.checkClash(draft);
        return BookingResponse.from(draft, clashes);
    }

    @PostMapping("/{id}/confirm")
    public BookingResponse confirmDraft(@AuthenticationPrincipal OidcUser user,
                                        @org.springframework.web.bind.annotation.PathVariable java.util.UUID id) {
        Booking confirmed = bookingService.confirmDraft(user.getEmail(), id);
        return BookingResponse.from(confirmed, confirmed.getStatus() == BookingStatus.IN_PROGRESS);
    }

    @org.springframework.web.bind.annotation.DeleteMapping("/{id}")
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
    public List<BookingResponse> history(@AuthenticationPrincipal OidcUser user,
                                         @org.springframework.web.bind.annotation.RequestHeader(value = "X-User-Email", required = false) String headerEmail) {
        String email = user != null ? user.getEmail() : headerEmail;
        if (email == null || email.isBlank()) {
            throw new org.springframework.web.server.ResponseStatusException(
                    HttpStatus.UNAUTHORIZED, "Authentication required to view booking history");
        }
        return bookingService.history(email).stream().map(BookingResponse::from).toList();
    }
}
