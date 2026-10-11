
package com.mrlbs.booking;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;

@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    private final BookingService bookingService;

    public BookingController(BookingService bookingService) {
        this.bookingService = bookingService;
    }

    public record CreateBookingRequest(
            Long resourceId,
            LocalDateTime startTime,
            LocalDateTime endTime
    ) {}

    @PostMapping
    public ResponseEntity<Booking> createBooking(
            @RequestBody CreateBookingRequest request) {

        Booking booking = bookingService.createBooking(
                request.resourceId(),
                request.startTime(),
                request.endTime()
        );

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(booking);
    }
}
