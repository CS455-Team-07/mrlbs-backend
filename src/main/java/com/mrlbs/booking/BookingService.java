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
    private final org.springframework.transaction.PlatformTransactionManager transactionManager;
    private final BookingStatusEventRepository statusEventRepository;

    public BookingService(BookingRepository bookingRepository, LabResourceRepository resourceRepository,
                          CreditAccountRepository creditAccountRepository, BookingPriceCalculator priceCalculator) {
        this(bookingRepository, resourceRepository, creditAccountRepository, priceCalculator, null, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public BookingService(BookingRepository bookingRepository, LabResourceRepository resourceRepository,
                          CreditAccountRepository creditAccountRepository, BookingPriceCalculator priceCalculator,
                          @org.springframework.beans.factory.annotation.Autowired(required = false)
                          org.springframework.transaction.PlatformTransactionManager transactionManager,
                          BookingStatusEventRepository statusEventRepository) {
        this.bookingRepository = bookingRepository;
        this.resourceRepository = resourceRepository;
        this.creditAccountRepository = creditAccountRepository;
        this.priceCalculator = priceCalculator;
        this.transactionManager = transactionManager;
        this.statusEventRepository = statusEventRepository;
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

        try {
            validateDuration(resource, start, end);
            validateQuota(ownerEmail, resource);

            List<Booking> overlapping = bookingRepository.findOverlappingConfirmedForUpdate(resource.getId(), start, end);
            boolean hasOverlap = overlapping != null && overlapping.stream()
                    .anyMatch(b -> !b.getId().equals(booking.getId()));
            boolean hasClash = hasOverlap || checkBufferViolation(resource, start, end, overlapping);

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

            BookingStatus nextStatus = requiresApproval(resource) ? BookingStatus.PENDING_APPROVAL
                    : hasClash ? BookingStatus.IN_PROGRESS : BookingStatus.CONFIRMED;
            booking.setStatus(nextStatus);
            Booking saved = bookingRepository.save(booking);
            recordStatusChange(saved, BookingStatus.DRAFT, nextStatus, ownerEmail, null);
            return saved;
        } catch (RuntimeException e) {
            // If it didn't meet criteria, delete the draft from the database
            deleteDraftSafely(booking.getId());
            bookingRepository.delete(booking);
            throw e;
        }
    }

    private void deleteDraftSafely(UUID bookingId) {
        if (transactionManager != null) {
            try {
                org.springframework.transaction.support.TransactionTemplate template =
                        new org.springframework.transaction.support.TransactionTemplate(
                                transactionManager,
                                new org.springframework.transaction.support.DefaultTransactionDefinition(
                                        org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW));
                template.executeWithoutResult(status -> {
                    bookingRepository.deleteById(bookingId);
                });
                return;
            } catch (Exception ignored) {
            }
        }
        bookingRepository.deleteById(bookingId);
    }

    @Transactional
    public void deleteBooking(String ownerEmail, UUID bookingId) {
        validateIdentity(ownerEmail);
        Booking booking = bookingRepository.findById(bookingId).orElse(null);
        if (booking == null) {
            return;
        }
        if (!booking.getOwnerEmail().equalsIgnoreCase(ownerEmail)) {
            throw new BookingRejectedException(BookingRejectionReason.RESOURCE_NOT_FOUND,
                    "Booking does not belong to the authenticated member.");
        }
        // Keep submitted bookings for history and audit; a member's delete action cancels them.
        if (booking.getStatus() == BookingStatus.IN_PROGRESS || booking.getStatus() == BookingStatus.PENDING || booking.getStatus() == BookingStatus.CONFIRMED || booking.getStatus() == BookingStatus.PENDING_APPROVAL) {
            if (booking.getCreditCost() != null && booking.getCreditCost().signum() > 0) {
                creditAccountRepository.findForUpdate(ownerEmail).ifPresent(account -> {
                    account.credit(booking.getCreditCost());
                    creditAccountRepository.save(account);
                });
            }
            BookingStatus previousStatus = booking.getStatus();
            booking.setStatus(BookingStatus.CANCELLED);
            bookingRepository.save(booking);
            recordStatusChange(booking, previousStatus, BookingStatus.CANCELLED, ownerEmail, "Cancelled by member");
            return;
        }
        bookingRepository.delete(booking);
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

        List<Booking> overlapping = bookingRepository.findOverlappingConfirmedForUpdate(resource.getId(), start, end);
        boolean hasOverlap = overlapping != null && !overlapping.isEmpty();
        boolean hasClash = hasOverlap || checkBufferViolation(resource, start, end, overlapping);

        BigDecimal contention = BigDecimal.valueOf(1 + (overlapping != null ? overlapping.size() : 0));
        Duration duration = Duration.between(start, end);
        var quote = priceCalculator.calculate(duration, resource.getBaseRate(), BigDecimal.ONE, contention,
                new PricingPolicy(resource.getMinimumDurationHours(), resource.getMaximumContentionMultiplier()));

        CreditAccount account = creditAccountRepository.findForUpdate(ownerEmail).orElse(null);
        if (account == null || account.getBalance().compareTo(quote.totalCredits()) < 0) {
            throw new BookingRejectedException(BookingRejectionReason.INSUFFICIENT_CREDITS,
                    "The member does not have enough credits for this booking.");
        }

        BookingStatus initialStatus = requiresApproval(resource) ? BookingStatus.PENDING_APPROVAL
                : hasClash ? BookingStatus.IN_PROGRESS : BookingStatus.CONFIRMED;
        Booking booking = new Booking(ownerEmail, resource, start, end,
                initialStatus,
                quote.totalCredits());
        Booking saved = bookingRepository.save(booking);
        account.debit(quote.totalCredits());
        creditAccountRepository.save(account);
        recordStatusChange(saved, null, initialStatus, ownerEmail, null);
        return saved;
    }

    @Transactional(readOnly = true)
    public List<Booking> pendingApprovals() {
        return bookingRepository.findByStatusOrderByStartTimeAsc(BookingStatus.PENDING_APPROVAL);
    }

    @Transactional(readOnly = true)
    public Booking getBooking(UUID bookingId) {
        return bookingRepository.findWithResourceById(bookingId).orElseThrow(() ->
                new BookingRejectedException(BookingRejectionReason.RESOURCE_NOT_FOUND, "Booking was not found."));
    }

    @Transactional
    public Booking approve(UUID bookingId, String reviewerEmail, String note) {
        return decide(bookingId, reviewerEmail, BookingStatus.CONFIRMED, note);
    }

    @Transactional
    public Booking reject(UUID bookingId, String reviewerEmail, String note) {
        return decide(bookingId, reviewerEmail, BookingStatus.REJECTED, note);
    }

    @Transactional(readOnly = true)
    public List<BookingStatusEvent> statusHistory(UUID bookingId) {
        return statusEventRepository == null ? List.of()
                : statusEventRepository.findByBookingIdOrderByOccurredAtAsc(bookingId);
    }

    private Booking decide(UUID bookingId, String reviewerEmail, BookingStatus decision, String note) {
        validateIdentity(reviewerEmail);
        Booking booking = bookingRepository.findByIdForUpdate(bookingId).orElseThrow(() ->
                new BookingRejectedException(BookingRejectionReason.RESOURCE_NOT_FOUND, "Booking was not found."));
        if (booking.getStatus() != BookingStatus.PENDING_APPROVAL) {
            throw new IllegalStateException("Booking is not pending approval.");
        }
        if (decision == BookingStatus.REJECTED && booking.getCreditCost() != null
                && booking.getCreditCost().signum() > 0) {
            creditAccountRepository.findForUpdate(booking.getOwnerEmail()).ifPresent(account -> {
                account.credit(booking.getCreditCost());
                creditAccountRepository.save(account);
            });
        }
        booking.setStatus(decision);
        Booking saved = bookingRepository.save(booking);
        recordStatusChange(saved, BookingStatus.PENDING_APPROVAL, decision, reviewerEmail,
                note == null || note.isBlank() ? null : note.trim());
        return saved;
    }

    private boolean requiresApproval(LabResource resource) {
        BookingRule rule = resource.getBookingRule();
        return rule != null && rule.getBookingPolicy() == com.mrlbs.resource.BookingPolicy.APPROVAL_REQUIRED;
    }

    private void recordStatusChange(Booking booking, BookingStatus from, BookingStatus to,
                                    String actorEmail, String note) {
        if (statusEventRepository != null) {
            statusEventRepository.save(new BookingStatusEvent(booking.getId(), from, to, actorEmail,
                    LocalDateTime.now(), note));
        }
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
                ownerEmail, resource.getId(), List.of(BookingStatus.CONFIRMED, BookingStatus.PENDING, BookingStatus.PENDING_APPROVAL, BookingStatus.IN_PROGRESS));
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

    @Transactional(readOnly = true)
    public boolean checkClash(Booking booking) {
        if (booking == null || booking.getResource() == null) {
            return false;
        }
        LabResource resource = booking.getResource();
        LocalDateTime start = booking.getStartTime();
        LocalDateTime end = booking.getEndTime();
        List<Booking> overlapping = bookingRepository.findOverlappingConfirmed(resource.getId(), start, end);
        boolean hasOverlap = overlapping != null && overlapping.stream()
                .anyMatch(b -> booking.getId() == null || !b.getId().equals(booking.getId()));
        if (hasOverlap) {
            return true;
        }
        return checkBufferViolation(resource, start, end, overlapping);
    }

    private boolean checkBufferViolation(LabResource resource, LocalDateTime start, LocalDateTime end, List<Booking> directOverlaps) {
        BookingRule rule = resource.getBookingRule();
        if (rule == null || rule.getBufferTimeMinutes() == null || rule.getBufferTimeMinutes() <= 0) {
            return false;
        }
        LocalDateTime bufferStart = start.minusMinutes(rule.getBufferTimeMinutes());
        LocalDateTime bufferEnd = end.plusMinutes(rule.getBufferTimeMinutes());
        List<Booking> windowBookings = bookingRepository.findConfirmedInWindow(
                resource.getId(), bufferStart, bufferEnd);
        if (windowBookings != null) {
            return windowBookings.stream()
                    .anyMatch(b -> directOverlaps == null || !directOverlaps.contains(b));
        }
        return false;
    }

    private void validateBufferTime(LabResource resource, LocalDateTime start, LocalDateTime end, List<Booking> directOverlaps) {
        if (checkBufferViolation(resource, start, end, directOverlaps)) {
            BookingRule rule = resource.getBookingRule();
            throw new BookingRejectedException(BookingRejectionReason.BUFFER_TIME_VIOLATION,
                    "Booking violates the required buffer time of " + rule.getBufferTimeMinutes() + " minutes between sessions.");
        }
    }

    @Transactional(readOnly = true)
    public List<Booking> history(String ownerEmail) {
        return bookingRepository.findByOwnerEmailOrderByStartTimeDesc(ownerEmail);
    }
}
