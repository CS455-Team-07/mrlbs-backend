package com.mrlbs.booking;

public class BookingRejectedException extends RuntimeException {
    private final BookingRejectionReason reason;

    public BookingRejectedException(BookingRejectionReason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public BookingRejectionReason getReason() { return reason; }
}
