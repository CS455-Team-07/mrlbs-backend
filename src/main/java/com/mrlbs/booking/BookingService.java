package com.mrlbs.booking;

import com.mrlbs.booking.credit.CreditAccount;
import com.mrlbs.booking.credit.CreditAccountRepository;
import com.mrlbs.booking.pricing.BookingPriceCalculator;
import com.mrlbs.booking.pricing.PricingPolicy;
import com.mrlbs.resource.BookingRule;
import com.mrlbs.resource.LabResource;
import com.mrlbs.resource.LabResourceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
public class BookingService {
    private final BookingRepository bookingRepository;
    private final LabResourceRepository resourceRepository;
    private final CreditAccountRepository creditAccountRepository;
    private final BookingPriceCalculator priceCalculator;

    public BookingService(BookingRepository bookingRepository, LabResourceRepository resourceRepository,
                          CreditAccountRepository creditAccountRepository, BookingPriceCalculator priceCalculator) {
        this.bookingRepository = bookingRepository;
        this.resourceRepository = resourceRepository;
        this.creditAccountRepository = creditAccountRepository;
        this.priceCalculator = priceCalculator;
    }

    @Transactional
    public Booking createDraft(String ownerEmail, BookingRequest request) {
        validateIdentity(ownerEmail);
        validateTimeWindow(request);
        LabResource resource = getResource(request.resourceId());
        LocalDateTime start = request.startTime();
        LocalDateTime end = request.endTime();

        validateDuration(resource, start, end);
        validateQuota(ownerEmail, resource);

        List<Booking> overlapping = bookingRepository.findOverlappingConfirmed(resource.getId(), start, end);
        BigDecimal contention = BigDecimal.valueOf(1 + (overlapping != null ? overlapping.size() : 0));
        Duration duration = Duration.between(start, end);
        var quote = priceCalculator.calculate(duration, resource.getBaseRate(), BigDecimal.ONE, contention,
                new PricingPolicy(resource.getMinimumDurationHours(), resource.getMaximumContentionMultiplier()));

        Booking draft = new Booking(ownerEmail, resource, start, end, BookingStatus.DRAFT, quote.totalCredits());
        return bookingRepository.save(draft);
    }

    @Transactional
    public Booking confirmDraft(String ownerEmail, UUID bookingId) {
        validateIdentity(ownerEmail);
        Booking booking = bookingRepository.findById(bookingId).orElseThrow(() ->
                new BookingRejectedException(BookingRejectionReason.RESOURCE_NOT_FOUND,
                        "Booking was not found."));

        if (!booking.getOwnerEmail().equalsIgnoreCase(ownerEmail)) {
            throw new BookingRejectedException(BookingRejectionReason.RESOURCE_NOT_FOUND,
                    "Booking does not belong to the authenticated member.");
        }

        if (booking.getStatus() != BookingStatus.DRAFT) {
            throw new IllegalStateException("Booking is not in draft status.");
        }

        LabResource resource = booking.getResource();
        LocalDateTime start = booking.getStartTime();
        LocalDateTime end = booking.getEndTime();

        validateDuration(resource, start, end);
        validateQuota(ownerEmail, resource);

        List<Booking> overlapping = acquireSlotWithPessimisticLock(resource, start, end);
        validateBufferTime(resource, start, end, overlapping);

        BigDecimal contention = BigDecimal.valueOf(1 + (overlapping != null ? overlapping.size() : 0));
        Duration duration = Duration.between(start, end);
        var quote = priceCalculator.calculate(duration, resource.getBaseRate(), BigDecimal.ONE, contention,
                new PricingPolicy(resource.getMinimumDurationHours(), resource.getMaximumContentionMultiplier()));
        booking.setCreditCost(quote.totalCredits());

        CreditAccount account = creditAccountRepository.findForUpdate(ownerEmail).orElse(null);
        if (account == null || account.getBalance().compareTo(quote.totalCredits()) < 0) {
            throw new BookingRejectedException(BookingRejectionReason.INSUFFICIENT_CREDITS,
                    "The member does not have enough credits for this booking.");
        }

        account.debit(quote.totalCredits());
        creditAccountRepository.save(account);

        booking.setStatus(BookingStatus.CONFIRMED);
        return bookingRepository.save(booking);
    }

    @Transactional
    public Booking submit(String ownerEmail, BookingRequest request) {
        validateIdentity(ownerEmail);
        validateTimeWindow(request);
        LabResource resource = getResource(request.resourceId());
        LocalDateTime start = request.startTime();
        LocalDateTime end = request.endTime();

        validateDuration(resource, start, end);
        validateQuota(ownerEmail, resource);

        List<Booking> overlapping = acquireSlotWithPessimisticLock(resource, start, end);
        validateBufferTime(resource, start, end, overlapping);

        BigDecimal contention = BigDecimal.valueOf(1 + (overlapping != null ? overlapping.size() : 0));
        Duration duration = Duration.between(start, end);
        var quote = priceCalculator.calculate(duration, resource.getBaseRate(), BigDecimal.ONE, contention,
                new PricingPolicy(resource.getMinimumDurationHours(), resource.getMaximumContentionMultiplier()));

        CreditAccount account = creditAccountRepository.findForUpdate(ownerEmail).orElse(null);
        if (account == null || account.getBalance().compareTo(quote.totalCredits()) < 0) {
            throw new BookingRejectedException(BookingRejectionReason.INSUFFICIENT_CREDITS,
                    "The member does not have enough credits for this booking.");
        }

        Booking booking = new Booking(ownerEmail, resource, start, end, BookingStatus.CONFIRMED,
                quote.totalCredits());
        Booking saved = bookingRepository.save(booking);
        account.debit(quote.totalCredits());
        creditAccountRepository.save(account);
        return saved;
    }

    private void validateIdentity(String ownerEmail) {
        if (ownerEmail == null || ownerEmail.isBlank()) {
            throw new IllegalArgumentException("Authenticated member identity is required");
        }
    }

    private void validateTimeWindow(BookingRequest request) {
        LocalDateTime start = request.startTime();
        LocalDateTime end = request.endTime();
        if (start == null || end == null || !end.isAfter(start) || !start.isAfter(LocalDateTime.now())) {
            throw new BookingRejectedException(BookingRejectionReason.INVALID_TIME_WINDOW,
                    "The requested booking time window is invalid.");
        }
    }

    private LabResource getResource(UUID resourceId) {
        return resourceRepository.findById(resourceId).orElseThrow(() ->
                new BookingRejectedException(BookingRejectionReason.RESOURCE_NOT_FOUND,
                        "The requested resource is not available."));
    }

    private void validateDuration(LabResource resource, LocalDateTime start, LocalDateTime end) {
        BookingRule rule = resource.getBookingRule();
        if (rule == null) {
            return;
        }
        long minutes = Duration.between(start, end).toMinutes();
        if (rule.getMinDurationMinutes() != null && minutes < rule.getMinDurationMinutes()) {
            throw new BookingRejectedException(BookingRejectionReason.DURATION_TOO_SHORT,
                    "Booking duration must be at least " + rule.getMinDurationMinutes() + " minutes.");
        }
        if (rule.getMaxDurationMinutes() != null && minutes > rule.getMaxDurationMinutes()) {
            throw new BookingRejectedException(BookingRejectionReason.DURATION_TOO_LONG,
                    "Booking duration cannot exceed " + rule.getMaxDurationMinutes() + " minutes.");
        }
    }

    private void validateQuota(String ownerEmail, LabResource resource) {
        BookingRule rule = resource.getBookingRule();
        if (rule == null || rule.getMaxBookingsPerMember() == null || rule.getMaxBookingsPerMember() <= 0) {
            return;
        }
        long activeCount = bookingRepository.countActiveBookingsForMember(
                ownerEmail, resource.getId(), List.of(BookingStatus.CONFIRMED, BookingStatus.IN_PROGRESS));
        if (activeCount >= rule.getMaxBookingsPerMember()) {
            throw new BookingRejectedException(BookingRejectionReason.QUOTA_EXCEEDED,
                    "Member has reached the quota of " + rule.getMaxBookingsPerMember() + " active bookings for this resource.");
        }
    }

    private List<Booking> acquireSlotWithPessimisticLock(LabResource resource, LocalDateTime start, LocalDateTime end) {
        List<Booking> overlapping = bookingRepository.findOverlappingConfirmedForUpdate(
                resource.getId(), start, end);
        int capacity = (resource.getCapacity() != null && resource.getCapacity() > 0) ? resource.getCapacity() : 1;
        if (overlapping != null && overlapping.size() >= capacity) {
            throw new BookingRejectedException(BookingRejectionReason.SLOT_NO_LONGER_AVAILABLE,
                    "This slot is no longer available. Please choose another time.");
        }
        return overlapping != null ? overlapping : List.of();
    }

    private void validateBufferTime(LabResource resource, LocalDateTime start, LocalDateTime end, List<Booking> directOverlaps) {
        BookingRule rule = resource.getBookingRule();
        if (rule == null || rule.getBufferTimeMinutes() == null || rule.getBufferTimeMinutes() <= 0) {
            return;
        }
        LocalDateTime bufferStart = start.minusMinutes(rule.getBufferTimeMinutes());
        LocalDateTime bufferEnd = end.plusMinutes(rule.getBufferTimeMinutes());
        List<Booking> windowBookings = bookingRepository.findConfirmedInWindow(
                resource.getId(), bufferStart, bufferEnd);
        if (windowBookings != null) {
            boolean hasBufferViolation = windowBookings.stream()
                    .anyMatch(b -> directOverlaps == null || !directOverlaps.contains(b));
            if (hasBufferViolation) {
                throw new BookingRejectedException(BookingRejectionReason.BUFFER_TIME_VIOLATION,
                        "Booking violates the required buffer time of " + rule.getBufferTimeMinutes() + " minutes between sessions.");
            }
        }
    }

    @Transactional(readOnly = true)
    public List<Booking> history(String ownerEmail) {
        return bookingRepository.findByOwnerEmailOrderByStartTimeDesc(ownerEmail);
    }
}
