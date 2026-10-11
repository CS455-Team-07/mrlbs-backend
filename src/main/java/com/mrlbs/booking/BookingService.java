package com.mrlbs.booking;

import com.mrlbs.booking.credit.CreditAccount;
import com.mrlbs.booking.credit.CreditAccountRepository;
import com.mrlbs.booking.pricing.BookingPriceCalculator;
import com.mrlbs.booking.pricing.PricingPolicy;
import com.mrlbs.resource.LabResource;
import com.mrlbs.resource.ResourceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;

@Service
public class BookingService {
    private final BookingRepository bookingRepository;
    private final ResourceRepository resourceRepository;
    private final CreditAccountRepository creditAccountRepository;
    private final BookingPriceCalculator priceCalculator;

    public BookingService(BookingRepository bookingRepository, ResourceRepository resourceRepository,
                          CreditAccountRepository creditAccountRepository, BookingPriceCalculator priceCalculator) {
        this.bookingRepository = bookingRepository;
        this.resourceRepository = resourceRepository;
        this.creditAccountRepository = creditAccountRepository;
        this.priceCalculator = priceCalculator;
    }

    @Transactional
    public Booking submit(String ownerEmail, BookingRequest request) {
        if (ownerEmail == null || ownerEmail.isBlank()) {
            throw new IllegalArgumentException("Authenticated member identity is required");
        }
        LocalDateTime start = request.startTime();
        LocalDateTime end = request.endTime();
        if (start == null || end == null || !end.isAfter(start) || !start.isAfter(LocalDateTime.now())) {
            throw new BookingRejectedException(BookingRejectionReason.INVALID_TIME_WINDOW,
                    "The requested booking time window is invalid.");
        }
        LabResource resource = resourceRepository.findById(request.resourceId()).orElseThrow(() ->
                new BookingRejectedException(BookingRejectionReason.RESOURCE_NOT_FOUND,
                        "The requested resource is not available."));
        Duration duration = Duration.between(start, end);
        var quote = priceCalculator.calculate(duration, resource.getBaseRate(), BigDecimal.ONE, BigDecimal.ONE,
                new PricingPolicy(resource.getMinimumDurationHours(), resource.getMaximumContentionMultiplier()));
        CreditAccount account = creditAccountRepository.findForUpdate(ownerEmail).orElse(null);
        if (account == null || account.getBalance().compareTo(quote.totalCredits()) < 0) {
            throw new BookingRejectedException(BookingRejectionReason.INSUFFICIENT_CREDITS,
                    "The member does not have enough credits for this booking.");
        }

        BookingStatus status = resource.isApprovalRequired()
                ? BookingStatus.PENDING_APPROVAL : BookingStatus.CONFIRMED;
        Booking booking = new Booking(ownerEmail, resource, start, end, status,
                quote.totalCredits());
        Booking saved = bookingRepository.save(booking);
        if (status == BookingStatus.CONFIRMED) {
            account.debit(quote.totalCredits());
            creditAccountRepository.save(account);
        }
        return saved;
    }

    @Transactional(readOnly = true)
    public java.util.List<Booking> history(String ownerEmail) {
        return bookingRepository.findByOwnerEmailOrderByStartTimeDesc(ownerEmail);
    }

    @Transactional(readOnly = true)
    public java.util.List<Booking> pendingApprovals() {
        return bookingRepository.findByStatusOrderByStartTimeAsc(BookingStatus.PENDING_APPROVAL);
    }
}
