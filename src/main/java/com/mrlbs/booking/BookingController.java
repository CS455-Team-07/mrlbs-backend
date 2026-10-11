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

    @GetMapping
    public List<BookingResponse> history(@AuthenticationPrincipal OidcUser user) {
        return bookingService.history(user.getEmail()).stream().map(BookingResponse::from).toList();
    }
}
