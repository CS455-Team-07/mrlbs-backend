package com.mrlbs.booking;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

public record BookingResponse(
        UUID id,
        UUID resourceId,
        String resourceName,
        LocalDateTime startTime,
        LocalDateTime endTime,
        BookingStatus status,
        BigDecimal creditCost,
        Boolean clashes,
        UUID memberId,
        Boolean waitlist,
        Long queuePosition,
        BigDecimal memberBalance,
        String rejectionReason
) {
    public BookingResponse(UUID id, UUID resourceId, String resourceName, LocalDateTime startTime, LocalDateTime endTime,
                           BookingStatus status, BigDecimal creditCost, Boolean clashes, UUID memberId) {
        this(id, resourceId, resourceName, startTime, endTime, status, creditCost, clashes, memberId, false, null, null, null);
    }

    public BookingResponse(UUID id, UUID resourceId, String resourceName, LocalDateTime startTime, LocalDateTime endTime,
                           BookingStatus status, BigDecimal creditCost, Boolean clashes, UUID memberId,
                           Boolean waitlist, Long queuePosition) {
        this(id, resourceId, resourceName, startTime, endTime, status, creditCost, clashes, memberId, waitlist, queuePosition, null, null);
    }

    public BookingResponse(UUID id, UUID resourceId, String resourceName, LocalDateTime startTime, LocalDateTime endTime,
                           BookingStatus status, BigDecimal creditCost, Boolean clashes, UUID memberId,
                           Boolean waitlist, Long queuePosition, BigDecimal memberBalance) {
        this(id, resourceId, resourceName, startTime, endTime, status, creditCost, clashes, memberId, waitlist, queuePosition, memberBalance, null);
    }

    public static BookingResponse from(Booking booking) {
        return from(booking, false, false, null, null);
    }

    public static BookingResponse from(Booking booking, boolean clashes) {
        return from(booking, clashes, clashes, null, null);
    }

    public static BookingResponse from(Booking booking, boolean clashes, boolean waitlist, Long queuePosition) {
        return from(booking, clashes, waitlist, queuePosition, null);
    }

    public static BookingResponse from(Booking booking, boolean clashes, boolean waitlist, Long queuePosition, BigDecimal memberBalance) {
        String resourceName = null;
        UUID resourceId = null;
        if (booking.getResource() != null) {
            try {
                resourceId = booking.getResource().getId();
                resourceName = booking.getResource().getName();
            } catch (Exception ignored) {
                resourceName = "Archived Resource";
            }
        }
        BookingStatus status = booking.getStatus();
        String rejectionReason = booking.getRejectionReason();
        if (status == BookingStatus.CONFIRMED && booking.getEndTime() != null && booking.getEndTime().isBefore(LocalDateTime.now())) {
            status = BookingStatus.COMPLETED;
        } else if (status == BookingStatus.PENDING_APPROVAL && booking.getStartTime() != null
                && !LocalDateTime.now().isBefore(booking.getStartTime().minusMinutes(15))) {
            status = BookingStatus.REJECTED;
            if (rejectionReason == null || rejectionReason.isBlank()) {
                rejectionReason = "Expired: Request was not reviewed by administrator before the deadline (15 minutes prior to start time).";
            }
        }
        return new BookingResponse(booking.getId(),
                resourceId,
                resourceName != null ? resourceName : "Archived Resource",
                booking.getStartTime(),
                booking.getEndTime(),
                status,
                booking.getCreditCost(),
                clashes,
                booking.getMemberId(),
                waitlist,
                queuePosition,
                memberBalance,
                rejectionReason);
    }
}
