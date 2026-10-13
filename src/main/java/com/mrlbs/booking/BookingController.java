package com.mrlbs.booking;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
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
    public BookingResponse submit(@AuthenticationPrincipal OAuth2User user,
                                  @Valid @RequestBody BookingRequest request) {
        return BookingResponse.from(bookingService.submit(user.getAttribute("email"), request));
    }

    @PostMapping("/draft")
    @ResponseStatus(HttpStatus.CREATED)
    public BookingResponse createDraft(@AuthenticationPrincipal OAuth2User user,
                                       @Valid @RequestBody BookingRequest request) {
        Booking draft = bookingService.createDraft(user.getAttribute("email"), request);
        boolean clashes = bookingService.checkClash(draft);
        return BookingResponse.from(draft, clashes);
    }

    @PostMapping("/{id}/confirm")
    public BookingResponse confirmDraft(@AuthenticationPrincipal OAuth2User user,
                                        @org.springframework.web.bind.annotation.PathVariable java.util.UUID id) {
        Booking confirmed = bookingService.confirmDraft(user.getAttribute("email"), id);
        return BookingResponse.from(confirmed, confirmed.getStatus() == BookingStatus.IN_PROGRESS);
    }

    @org.springframework.web.bind.annotation.DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteBooking(@AuthenticationPrincipal OAuth2User user,
                              @org.springframework.web.bind.annotation.PathVariable java.util.UUID id) {
        bookingService.deleteBooking(user.getAttribute("email"), id);
    }

    @GetMapping
    public List<BookingResponse> history(@AuthenticationPrincipal OAuth2User user) {
        return bookingService.history(user.getAttribute("email")).stream().map(BookingResponse::from).toList();
    }
}
