package com.mrlbs.booking.service;

import com.mrlbs.booking.Booking;
import com.mrlbs.booking.BookingStatus;
import com.mrlbs.booking.BookingRepository;
import com.mrlbs.resource.Resource;
import com.mrlbs.resource.ResourceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;

@Service
public class BookingService {
    private final BookingRepository bookingRepository;
    private final ResourceRepository resourceRepository;
    private final PricingProvider pricingProvider;
    private final ContentionProvider contentionProvider;

    public BookingService(BookingRepository bookingRepository,
                          ResourceRepository resourceRepository,
                          PricingProvider pricingProvider,
                          ContentionProvider contentionProvider) {
        this.bookingRepository = bookingRepository;
        this.resourceRepository = resourceRepository;
        this.pricingProvider = pricingProvider;
        this.contentionProvider = contentionProvider;
    }

    @Transactional
    public Booking createDraft(Long memberId, Long resourceId, LocalDateTime start, LocalDateTime end) {
        if (end.isBefore(start) || Duration.between(start, end).isZero() || Duration.between(start, end).isNegative()) {
            throw new IllegalArgumentException("End time must be after start time with positive duration");
        }
        Resource resource = resourceRepository.findById(resourceId)
                .orElseThrow(() -> new IllegalArgumentException("Resource not found id: " + resourceId));
        Booking booking = new Booking();
        booking.setMemberId(memberId);
        booking.setResource(resource);
        booking.setStartTime(start);
        booking.setEndTime(end);
        booking.setStatus(BookingStatus.DRAFT);
        // ---- Calculate credit cost for the draft ----
        // Duration in hours as BigDecimal (fractional hours allowed)
        Duration dur = Duration.between(start, end);
        BigDecimal durationHours = new BigDecimal(dur.toMinutes())
                .divide(new BigDecimal(60), 4, RoundingMode.HALF_UP);
        BigDecimal baseRate = resource.getBaseRate();
        if (baseRate == null) {
            throw new IllegalStateException("Base rate not configured for resource id: " + resource.getId());
        }
        BigDecimal mtime = pricingProvider.getMtimeMultiplier(resource, start, end);
        BigDecimal mcontention = contentionProvider.getMcontentionMultiplier(resource);
        // ((Duration * BaseRate) * Mtime * Mcontention)
        BigDecimal raw = durationHours.multiply(baseRate)
                .multiply(mtime)
                .multiply(mcontention);
        // ceil to nearest whole credit
        BigDecimal totalCost = raw.setScale(0, RoundingMode.CEILING);
        booking.setCreditCost(totalCost);
        // ------------------------------------------------
        return bookingRepository.save(booking);
    }

    @Transactional(readOnly = true)
    public Booking calculateCredits(Long bookingId, Long requestingMemberId) {
        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new IllegalArgumentException("Booking not found id: " + bookingId));
        if (!booking.getMemberId().equals(requestingMemberId)) {
            throw new SecurityException("Member does not own this booking");
        }
        // Duration in hours as BigDecimal (fractional hours allowed)
        Duration dur = Duration.between(booking.getStartTime(), booking.getEndTime());
        BigDecimal durationHours = new BigDecimal(dur.toMinutes()).divide(new BigDecimal(60), 4, RoundingMode.HALF_UP);
        BigDecimal baseRate = booking.getResource().getBaseRate();
        if (baseRate == null) {
            throw new IllegalStateException("Base rate not configured for resource id: " + booking.getResource().getId());
        }
        BigDecimal mtime = pricingProvider.getMtimeMultiplier(booking.getResource(), booking.getStartTime(), booking.getEndTime());
        BigDecimal mcontention = contentionProvider.getMcontentionMultiplier(booking.getResource());
        // ((Duration * BaseRate) * Mtime * Mcontention)
        BigDecimal raw = durationHours.multiply(baseRate)
                .multiply(mtime)
                .multiply(mcontention);
        // ceil to nearest whole credit
        BigDecimal totalCost = raw.setScale(0, RoundingMode.CEILING);
        booking.setCreditCost(totalCost);
        return bookingRepository.save(booking);
    }
}
