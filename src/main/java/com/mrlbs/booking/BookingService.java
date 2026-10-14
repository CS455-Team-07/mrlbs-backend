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
import com.mrlbs.member.MemberRepository;
import com.mrlbs.security.AdminResourceScope;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@Service
public class BookingService {
    private enum BookingConflictType {
        NONE,
        EXACT_MATCH,
        PARTIAL_CONFLICT
    }

    private final BookingRepository bookingRepository;
    private final LabResourceRepository resourceRepository;
    private final CreditAccountRepository creditAccountRepository;
    private final BookingPriceCalculator priceCalculator;
    private final org.springframework.transaction.PlatformTransactionManager transactionManager;

    private final MemberRepository memberRepository;
    private final com.mrlbs.waitlist.WaitlistEntryRepository waitlistRepository;
    private final com.mrlbs.waitlist.WaitlistAllocationService waitlistAllocationService;
    private final com.mrlbs.waitlist.WaitlistOfferService waitlistOfferService;
    private final com.mrlbs.waitlist.WaitlistService waitlistService;

    public BookingService(BookingRepository bookingRepository, LabResourceRepository resourceRepository,
                          CreditAccountRepository creditAccountRepository, BookingPriceCalculator priceCalculator) {
        this(bookingRepository, resourceRepository, creditAccountRepository, priceCalculator, null, null, null, null, null, null);
    }

    public BookingService(BookingRepository bookingRepository, LabResourceRepository resourceRepository,
                          CreditAccountRepository creditAccountRepository, BookingPriceCalculator priceCalculator,
                          MemberRepository memberRepository) {
        this(bookingRepository, resourceRepository, creditAccountRepository, priceCalculator, null, memberRepository, null, null, null, null);
    }

    public BookingService(BookingRepository bookingRepository, LabResourceRepository resourceRepository,
                          CreditAccountRepository creditAccountRepository, BookingPriceCalculator priceCalculator,
                          org.springframework.transaction.PlatformTransactionManager transactionManager,
                          MemberRepository memberRepository) {
        this(bookingRepository, resourceRepository, creditAccountRepository, priceCalculator, transactionManager, memberRepository, null, null, null, null);
    }

    public BookingService(BookingRepository bookingRepository, LabResourceRepository resourceRepository,
                          CreditAccountRepository creditAccountRepository, BookingPriceCalculator priceCalculator,
                          org.springframework.transaction.PlatformTransactionManager transactionManager,
                          MemberRepository memberRepository,
                          com.mrlbs.waitlist.WaitlistEntryRepository waitlistRepository) {
        this(bookingRepository, resourceRepository, creditAccountRepository, priceCalculator, transactionManager, memberRepository, waitlistRepository, null, null, null);
    }

    public BookingService(BookingRepository bookingRepository, LabResourceRepository resourceRepository,
                          CreditAccountRepository creditAccountRepository, BookingPriceCalculator priceCalculator,
                          org.springframework.transaction.PlatformTransactionManager transactionManager,
                          MemberRepository memberRepository,
                          com.mrlbs.waitlist.WaitlistEntryRepository waitlistRepository,
                          com.mrlbs.waitlist.WaitlistAllocationService waitlistAllocationService,
                          com.mrlbs.waitlist.WaitlistOfferService waitlistOfferService) {
        this(bookingRepository, resourceRepository, creditAccountRepository, priceCalculator, transactionManager, memberRepository, waitlistRepository, waitlistAllocationService, waitlistOfferService, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public BookingService(BookingRepository bookingRepository, LabResourceRepository resourceRepository,
                          CreditAccountRepository creditAccountRepository, BookingPriceCalculator priceCalculator,
                          @org.springframework.beans.factory.annotation.Autowired(required = false)
                          org.springframework.transaction.PlatformTransactionManager transactionManager,
                          MemberRepository memberRepository,
                          @org.springframework.beans.factory.annotation.Autowired(required = false)
                          com.mrlbs.waitlist.WaitlistEntryRepository waitlistRepository,
                          @org.springframework.beans.factory.annotation.Autowired(required = false)
                          com.mrlbs.waitlist.WaitlistAllocationService waitlistAllocationService,
                          @org.springframework.beans.factory.annotation.Autowired(required = false)
                          com.mrlbs.waitlist.WaitlistOfferService waitlistOfferService,
                          @org.springframework.beans.factory.annotation.Autowired(required = false)
                          com.mrlbs.waitlist.WaitlistService waitlistService) {
        this.bookingRepository = bookingRepository;
        this.resourceRepository = resourceRepository;
        this.creditAccountRepository = creditAccountRepository;
        this.priceCalculator = priceCalculator;
        this.transactionManager = transactionManager;
        this.memberRepository = memberRepository;
        this.waitlistRepository = waitlistRepository;
        this.waitlistAllocationService = waitlistAllocationService;
        this.waitlistOfferService = waitlistOfferService;
        this.waitlistService = waitlistService;
    }

    public BigDecimal getMemberBalance(String email) {
        if (email == null || creditAccountRepository == null) {
            return BigDecimal.ZERO;
        }
        return creditAccountRepository.findById(email)
                .map(CreditAccount::getBalance)
                .orElse(BigDecimal.ZERO);
    }

    public long calculateNextQueueNumber(UUID resourceId, LocalDateTime start, LocalDateTime end) {
        if (waitlistService != null) {
            return waitlistService.getNextQueuePosition(resourceId, start, end);
        }
        if (waitlistRepository != null && resourceId != null) {
            List<com.mrlbs.waitlist.WaitlistEntry> active = waitlistRepository.findActiveQueue(resourceId, start, end);
            return (active != null ? active.size() : 0) + 1L;
        }
        return 1L;
    }

    public BigDecimal calculateWaitlistContention(LabResource resource, long queueNumber) {
        BigDecimal base = BigDecimal.ONE.add(new BigDecimal("0.1").multiply(BigDecimal.valueOf(queueNumber)));
        BigDecimal max = (resource != null && resource.getMaximumContentionMultiplier() != null)
                ? resource.getMaximumContentionMultiplier()
                : new BigDecimal("3.00");
        return base.min(max);
    }


    @Transactional
    public Booking createDraft(String ownerEmail, BookingRequest request) {
        UUID memberId = resolveMemberId(ownerEmail);
        validateIdentity(ownerEmail);
        validateTimeWindow(request);
        LabResource resource = getResource(request.resourceId());
        LocalDateTime start = request.startTime();
        LocalDateTime end = request.endTime();

        validateDuration(resource, start, end);
        validateQuota(memberId, resource);

        List<Booking> overlapping = bookingRepository.findOverlappingConfirmed(resource.getId(), start, end);
        BookingConflictType conflict = detectConflict(resource, start, end, overlapping, null);
        if (conflict == BookingConflictType.PARTIAL_CONFLICT) {
            throw new BookingRejectedException(BookingRejectionReason.SLOT_CONFLICT,
                    "This time window clashes with an already booked slot. Please select the exact slot to go to waitlist or choose a different time.");
        }

        if (conflict == BookingConflictType.EXACT_MATCH && waitlistRepository != null) {
            Booking parentBooking = findParentBooking(overlapping, start, end);
            LocalDateTime wlStart = parentBooking != null ? parentBooking.getStartTime() : start;
            LocalDateTime wlEnd = parentBooking != null ? parentBooking.getEndTime() : end;
            boolean alreadyWaiting = waitlistRepository.existsActiveWaitlist(
                    memberId, resource.getId(), wlStart, wlEnd);
            if (alreadyWaiting) {
                throw new BookingRejectedException(BookingRejectionReason.DUPLICATE_WAITLIST,
                        "You already have an active waitlist request for this resource and time slot.");
            }
        }

        BigDecimal contention = BigDecimal.valueOf(1 + (overlapping != null ? overlapping.size() : 0));
        if (conflict == BookingConflictType.EXACT_MATCH) {
            long queueNumber = calculateNextQueueNumber(resource.getId(), start, end);
            contention = calculateWaitlistContention(resource, queueNumber);
        }
        Duration duration = Duration.between(start, end);
        var quote = priceCalculator.calculate(duration, resource.getBaseRate(), BigDecimal.ONE, contention,
                new PricingPolicy(resource.getMinimumDurationHours(), resource.getMaximumContentionMultiplier()));

        Booking draft = new Booking(ownerEmail, resource, start, end, BookingStatus.DRAFT, quote.totalCredits());
        draft.setMemberId(memberId);
        return bookingRepository.save(draft);
    }

    @Transactional
    public Booking confirmDraft(String ownerEmail, UUID bookingId) {
        UUID memberId = resolveMemberId(ownerEmail);
        validateIdentity(ownerEmail);
        Booking booking = bookingRepository.findById(bookingId).orElseThrow(() ->
                new BookingRejectedException(BookingRejectionReason.RESOURCE_NOT_FOUND,
                        "Booking was not found."));

        if (!isBookingOwner(booking, memberId, ownerEmail)) {
            throw new BookingRejectedException(BookingRejectionReason.RESOURCE_NOT_FOUND,
                    "Booking does not belong to the authenticated member.");
        }

        if (booking.getStatus() != BookingStatus.DRAFT) {
            throw new IllegalStateException("Booking is not in draft status.");
        }

        LabResource resource = getResourceForUpdate(booking.getResource() != null ? booking.getResource().getId() : null, booking.getResource());
        if (resource == null) {
            throw new BookingRejectedException(BookingRejectionReason.RESOURCE_NOT_FOUND,
                    "The requested resource is not available.");
        }
        LocalDateTime start = booking.getStartTime();
        LocalDateTime end = booking.getEndTime();

        try {
            validateDuration(resource, start, end);
            validateQuota(memberId, resource);

            List<Booking> overlapping = bookingRepository.findOverlappingConfirmedForUpdate(resource.getId(), start, end);
            BookingConflictType conflict = detectConflict(resource, start, end, overlapping, booking);
            if (conflict == BookingConflictType.PARTIAL_CONFLICT) {
                throw new BookingRejectedException(BookingRejectionReason.SLOT_CONFLICT,
                        "This time window clashes with an already booked slot. Please select the exact slot to go to waitlist or choose a different time.");
            }

            if (conflict == BookingConflictType.EXACT_MATCH) {
                Booking parentBooking = findParentBooking(overlapping, start, end);
                LocalDateTime wlStart = parentBooking != null ? parentBooking.getStartTime() : start;
                LocalDateTime wlEnd = parentBooking != null ? parentBooking.getEndTime() : end;

                if (waitlistRepository != null) {
                    boolean alreadyWaiting = waitlistRepository.existsActiveWaitlist(
                            memberId, resource.getId(), wlStart, wlEnd);
                    if (alreadyWaiting) {
                        throw new BookingRejectedException(BookingRejectionReason.DUPLICATE_WAITLIST,
                                "You already have an active waitlist request for this resource and time slot.");
                    }
                }

                long queueNumber = calculateNextQueueNumber(resource.getId(), start, end);
                BigDecimal contention = calculateWaitlistContention(resource, queueNumber);
                Duration duration = Duration.between(start, end);
                var quote = priceCalculator.calculate(duration, resource.getBaseRate(), BigDecimal.ONE, contention,
                        new PricingPolicy(resource.getMinimumDurationHours(), resource.getMaximumContentionMultiplier()));

                // Deduct credits from member balance when booking state is IN_PROGRESS
                CreditAccount account = creditAccountRepository.findForUpdate(ownerEmail).orElse(null);
                if (account == null || account.getBalance().compareTo(quote.totalCredits()) < 0) {
                    throw new BookingRejectedException(BookingRejectionReason.INSUFFICIENT_CREDITS,
                            "The member does not have enough credits for this booking.");
                }

                account.debit(quote.totalCredits());
                creditAccountRepository.save(account);

                booking.setCreditCost(quote.totalCredits());
                booking.setStatus(BookingStatus.IN_PROGRESS);
                Booking savedBooking = bookingRepository.save(booking);

                if (waitlistRepository != null) {
                    com.mrlbs.waitlist.WaitlistEntry entry = new com.mrlbs.waitlist.WaitlistEntry(
                            memberId,
                            resource,
                            start,
                            end,
                            BookingStatus.IN_PROGRESS,
                            savedBooking.getId(),
                            quote.totalCredits(),
                            quote.appliedContentionMultiplier()
                    );
                    waitlistRepository.save(entry);
                }

                return savedBooking;
            }

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

            booking.setStatus(resolveBookingStatus(resource, conflict));
            return bookingRepository.save(booking);
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
    public Booking deleteBooking(String ownerEmail, UUID bookingId) {
        UUID memberId = resolveMemberId(ownerEmail);
        validateIdentity(ownerEmail);
        Booking booking = null;
        try {
            booking = bookingRepository.findByIdForUpdate(bookingId)
                    .orElseGet(() -> bookingRepository.findById(bookingId).orElse(null));
        } catch (Exception e) {
            booking = bookingRepository.findById(bookingId).orElse(null);
        }
        if (booking == null) {
            return null;
        }
        if (!isBookingOwner(booking, memberId, ownerEmail)) {
            throw new BookingRejectedException(BookingRejectionReason.RESOURCE_NOT_FOUND,
                    "Booking does not belong to the authenticated member.");
        }
        if (booking.getStatus() == BookingStatus.CANCELLED) {
            return booking;
        }
        boolean wasConfirmedOrApproved = booking.getStatus() == BookingStatus.CONFIRMED
                || booking.getStatus() == BookingStatus.PENDING_APPROVAL
                || booking.getStatus() == BookingStatus.PENDING;
        boolean wasDebitEligible = wasConfirmedOrApproved
                || booking.getStatus() == BookingStatus.IN_PROGRESS;

        // If credits were already debited for a confirmed, pending, or IN_PROGRESS booking, refund them
        if (wasDebitEligible) {
            BigDecimal refundCost = booking.getCreditCost();
            if (refundCost != null && refundCost.signum() > 0) {
                creditAccountRepository.findForUpdate(ownerEmail).ifPresent(account -> {
                    account.credit(refundCost);
                    creditAccountRepository.save(account);
                });
            }
        }
        // If draft, remove the unconfirmed draft
        if (booking.getStatus() == BookingStatus.DRAFT) {
            bookingRepository.delete(booking);
            return null;
        }

        // Active booking: persist in DB with CANCELLED status and set cancelled timestamp
        booking.setStatus(BookingStatus.CANCELLED);
        booking.setCancelledAt(LocalDateTime.now());
        Booking saved = bookingRepository.save(booking);

        if (waitlistRepository != null) {
            waitlistRepository.findByBookingId(booking.getId()).ifPresent(entry -> {
                if (entry.getStatus() == BookingStatus.IN_PROGRESS) {
                    entry.setStatus(BookingStatus.CANCELLED);
                    entry.setLeftAt(LocalDateTime.now());
                    waitlistRepository.save(entry);
                }
            });
        }

        if (wasConfirmedOrApproved && saved.getResource() != null) {
            if (waitlistOfferService != null && waitlistOfferService.isOfferModeEnabled()) {
                waitlistOfferService.processReleasedSlotForOffer(
                        saved.getResource().getId(),
                        saved.getStartTime(),
                        saved.getEndTime()
                );
            } else if (waitlistAllocationService != null) {
                waitlistAllocationService.processReleasedSlot(
                        saved.getResource().getId(),
                        saved.getStartTime(),
                        saved.getEndTime()
                );
            }
        }

        return saved;
    }



    

    @Transactional
    public Booking submit(String ownerEmail, BookingRequest request) {
        UUID memberId = resolveMemberId(ownerEmail);
        validateIdentity(ownerEmail);
        validateTimeWindow(request);
        LabResource resource = getResourceForUpdate(request.resourceId(), null);
        if (resource == null) {
            throw new BookingRejectedException(BookingRejectionReason.RESOURCE_NOT_FOUND,
                    "The requested resource is not available.");
        }
        LocalDateTime start = request.startTime();
        LocalDateTime end = request.endTime();

        validateDuration(resource, start, end);
        validateQuota(memberId, resource);

        List<Booking> overlapping = bookingRepository.findOverlappingConfirmedForUpdate(resource.getId(), start, end);
        BookingConflictType conflict = detectConflict(resource, start, end, overlapping, null);
        if (conflict == BookingConflictType.PARTIAL_CONFLICT) {
            throw new BookingRejectedException(BookingRejectionReason.SLOT_CONFLICT,
                    "This time window clashes with an already booked slot. Please select the exact slot to go to waitlist or choose a different time.");
        }

        if (conflict == BookingConflictType.EXACT_MATCH) {
            Booking parentBooking = findParentBooking(overlapping, start, end);
            LocalDateTime wlStart = parentBooking != null ? parentBooking.getStartTime() : start;
            LocalDateTime wlEnd = parentBooking != null ? parentBooking.getEndTime() : end;

            if (waitlistRepository != null) {
                boolean alreadyWaiting = waitlistRepository.existsActiveWaitlist(
                        memberId, resource.getId(), wlStart, wlEnd);
                if (alreadyWaiting) {
                    throw new BookingRejectedException(BookingRejectionReason.DUPLICATE_WAITLIST,
                            "You already have an active waitlist request for this resource and time slot.");
                }
            }

            long queueNumber = calculateNextQueueNumber(resource.getId(), start, end);
            BigDecimal contention = calculateWaitlistContention(resource, queueNumber);
            Duration duration = Duration.between(start, end);
            var quote = priceCalculator.calculate(duration, resource.getBaseRate(), BigDecimal.ONE, contention,
                    new PricingPolicy(resource.getMinimumDurationHours(), resource.getMaximumContentionMultiplier()));

            CreditAccount account = creditAccountRepository.findForUpdate(ownerEmail).orElse(null);
            if (account == null || account.getBalance().compareTo(quote.totalCredits()) < 0) {
                throw new BookingRejectedException(BookingRejectionReason.INSUFFICIENT_CREDITS,
                        "The member does not have enough credits for this booking.");
            }

            account.debit(quote.totalCredits());
            creditAccountRepository.save(account);

            Booking booking = new Booking(ownerEmail, resource, start, end,
                    BookingStatus.IN_PROGRESS,
                    quote.totalCredits());
            booking.setMemberId(memberId);
            Booking saved = bookingRepository.save(booking);

            if (waitlistRepository != null) {
                com.mrlbs.waitlist.WaitlistEntry entry = new com.mrlbs.waitlist.WaitlistEntry(
                        memberId,
                        resource,
                        start,
                        end,
                        BookingStatus.IN_PROGRESS,
                        saved.getId(),
                        quote.totalCredits(),
                        quote.appliedContentionMultiplier()
                );
                waitlistRepository.save(entry);
            }

            return saved;
        }

        BigDecimal contention = BigDecimal.valueOf(1 + (overlapping != null ? overlapping.size() : 0));
        Duration duration = Duration.between(start, end);
        var quote = priceCalculator.calculate(duration, resource.getBaseRate(), BigDecimal.ONE, contention,
                new PricingPolicy(resource.getMinimumDurationHours(), resource.getMaximumContentionMultiplier()));

        CreditAccount account = creditAccountRepository.findForUpdate(ownerEmail).orElse(null);
        if (account == null || account.getBalance().compareTo(quote.totalCredits()) < 0) {
            throw new BookingRejectedException(BookingRejectionReason.INSUFFICIENT_CREDITS,
                    "The member does not have enough credits for this booking.");
        }

        Booking booking = new Booking(ownerEmail, resource, start, end,
                resolveBookingStatus(resource, conflict),
                quote.totalCredits());
        booking.setMemberId(memberId);
        Booking saved = bookingRepository.save(booking);
        account.debit(quote.totalCredits());
        creditAccountRepository.save(account);
        return saved;
    }

    private BookingStatus resolveBookingStatus(LabResource resource, BookingConflictType conflict) {
        BookingRule rule = resource != null ? resource.getBookingRule() : null;
        if (rule != null && rule.getBookingPolicy() == com.mrlbs.resource.BookingPolicy.APPROVAL_REQUIRED) {
            return BookingStatus.PENDING_APPROVAL;
        }
        return conflict == BookingConflictType.EXACT_MATCH ? BookingStatus.IN_PROGRESS : BookingStatus.CONFIRMED;
    }

    @Transactional(readOnly = true)
    public List<Booking> pendingApprovalsForAdmin(String adminEmail, String scope) {
        validateIdentity(adminEmail);
        List<Booking> pending = bookingRepository.findByStatusOrderByStartTimeAsc(BookingStatus.PENDING_APPROVAL)
                .stream()
                .sorted(
                        Comparator.comparing((Booking booking) -> booking.getStartTime(), Comparator.nullsLast(LocalDateTime::compareTo))
                                .thenComparing(booking -> booking.getResource() != null ? booking.getResource().getId() : null,
                                        Comparator.nullsLast(Comparator.naturalOrder()))
                                .thenComparing(booking -> booking.getCreatedAt(), Comparator.nullsLast(LocalDateTime::compareTo)))
                .toList();
        if (scope == null || scope.isBlank()) {
            return pending;
        }

        return pending.stream()
                .filter(booking -> booking.getResource() != null
                        && booking.getResource().getType() != null
                        && booking.getResource().getType().equalsIgnoreCase(scope))
                .toList();
    }

    @Transactional
    public Booking reviewApprovalDecision(String adminEmail, UUID bookingId, boolean approved) {
        validateIdentity(adminEmail);
        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new BookingRejectedException(BookingRejectionReason.RESOURCE_NOT_FOUND,
                        "Booking was not found."));

        if (booking.getStatus() != BookingStatus.PENDING_APPROVAL) {
            throw new IllegalStateException("Booking is not awaiting approval.");
        }

        String scope = AdminResourceScope.scopeForEmail(adminEmail).orElse(null);
        if (scope != null && booking.getResource() != null && booking.getResource().getType() != null
                && !booking.getResource().getType().equalsIgnoreCase(scope)) {
            throw new BookingRejectedException(BookingRejectionReason.RESOURCE_NOT_FOUND,
                    "This admin cannot review bookings for the selected resource type.");
        }

        boolean wasWaitlisted = waitlistRepository != null && waitlistRepository.findByBookingId(booking.getId()).isPresent();

        if (approved) {
            if (wasWaitlisted && booking.getCreditCost() != null && booking.getCreditCost().signum() > 0) {
                String memberEmail = resolveOwnerEmail(booking);
                if (memberEmail != null && !memberEmail.isBlank()) {
                    CreditAccount account = creditAccountRepository.findForUpdate(memberEmail).orElse(null);
                    if (account == null || account.getBalance().compareTo(booking.getCreditCost()) < 0) {
                        throw new BookingRejectedException(BookingRejectionReason.INSUFFICIENT_CREDITS,
                                "The member does not have enough credits to confirm this booking.");
                    }
                    account.debit(booking.getCreditCost());
                    creditAccountRepository.save(account);
                }
            }
            booking.setStatus(BookingStatus.CONFIRMED);
            return bookingRepository.save(booking);
        }

        if (!wasWaitlisted && booking.getCreditCost() != null && booking.getCreditCost().signum() > 0) {
            String memberEmail = resolveOwnerEmail(booking);
            if (memberEmail != null && !memberEmail.isBlank()) {
                CreditAccount account = creditAccountRepository.findForUpdate(memberEmail).orElse(null);
                if (account != null) {
                    account.credit(booking.getCreditCost());
                    creditAccountRepository.save(account);
                }
            }
        }

        booking.setStatus(BookingStatus.REJECTED);
        booking.setCancelledAt(LocalDateTime.now());
        Booking saved = bookingRepository.save(booking);

        if (saved.getResource() != null) {
            if (waitlistOfferService != null && waitlistOfferService.isOfferModeEnabled()) {
                waitlistOfferService.processReleasedSlotForOffer(
                        saved.getResource().getId(),
                        saved.getStartTime(),
                        saved.getEndTime()
                );
            } else if (waitlistAllocationService != null) {
                waitlistAllocationService.processReleasedSlot(
                        saved.getResource().getId(),
                        saved.getStartTime(),
                        saved.getEndTime()
                );
            }
        }

        return saved;
    }

    public String resolveMemberEmail(Booking booking) {
        if (booking == null) {
            return null;
        }
        if (booking.getOwnerEmail() != null && !booking.getOwnerEmail().isBlank()) {
            return booking.getOwnerEmail();
        }
        if (booking.getMemberId() == null || memberRepository == null) {
            return null;
        }
        return memberRepository.findById(booking.getMemberId())
                .map(com.mrlbs.member.Member::getEmail)
                .orElse(null);
    }

    private String resolveOwnerEmail(Booking booking) {
        return resolveMemberEmail(booking);
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

    private LabResource getResourceForUpdate(UUID resourceId, LabResource fallback) {
        if (resourceRepository == null) {
            return fallback;
        }
        try {
            return resourceRepository.findByIdForUpdate(resourceId)
                    .or(() -> resourceRepository.findById(resourceId))
                    .orElse(fallback);
        } catch (Exception e) {
            return fallback;
        }
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

    private void validateQuota(UUID memberId, LabResource resource) {
        BookingRule rule = resource.getBookingRule();
        if (rule == null || rule.getMaxBookingsPerMember() == null || rule.getMaxBookingsPerMember() <= 0) {
            return;
        }
        long activeCount = bookingRepository.countActiveBookingsForMember(
                memberId, resource.getId(), List.of(BookingStatus.CONFIRMED, BookingStatus.PENDING, BookingStatus.PENDING_APPROVAL, BookingStatus.IN_PROGRESS));
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
        return detectConflict(resource, start, end, overlapping, booking) != BookingConflictType.NONE;
    }

    private BookingConflictType detectConflict(LabResource resource, LocalDateTime start, LocalDateTime end,
                                              List<Booking> overlapping, Booking currentBooking) {
        List<Booking> relevantOverlaps = filterOutCurrentBooking(currentBooking, overlapping);
        if (relevantOverlaps != null && !relevantOverlaps.isEmpty()) {
            boolean anyExactOrSubsetMatch = relevantOverlaps.stream()
                    .anyMatch(existing -> existing != null
                            && existing.getStartTime() != null
                            && existing.getEndTime() != null
                            && !start.isBefore(existing.getStartTime())
                            && !end.isAfter(existing.getEndTime()));
            if (anyExactOrSubsetMatch) {
                return BookingConflictType.EXACT_MATCH;
            }
            return BookingConflictType.PARTIAL_CONFLICT;
        }

        return checkBufferViolation(resource, start, end, relevantOverlaps, currentBooking)
                ? BookingConflictType.PARTIAL_CONFLICT
                : BookingConflictType.NONE;
    }

    private Booking findParentBooking(List<Booking> overlapping, LocalDateTime start, LocalDateTime end) {
        if (overlapping == null || overlapping.isEmpty()) {
            return null;
        }
        return overlapping.stream()
                .filter(b -> b != null && b.getStartTime() != null && b.getEndTime() != null
                        && b.getStatus() == BookingStatus.CONFIRMED
                        && !start.isBefore(b.getStartTime()) && !end.isAfter(b.getEndTime()))
                .findFirst()
                .orElse(null);
    }

    private List<Booking> filterOutCurrentBooking(Booking currentBooking, List<Booking> bookings) {
        if (bookings == null || bookings.isEmpty() || currentBooking == null || currentBooking.getId() == null) {
            return bookings == null ? List.of() : bookings;
        }
        return bookings.stream()
                .filter(existing -> existing != null
                        && (existing.getId() == null || !existing.getId().equals(currentBooking.getId())))
                .toList();
    }

    private boolean checkBufferViolation(LabResource resource, LocalDateTime start, LocalDateTime end,
                                        List<Booking> directOverlaps, Booking currentBooking) {
        BookingRule rule = resource.getBookingRule();
        if (rule == null || rule.getBufferTimeMinutes() == null || rule.getBufferTimeMinutes() <= 0) {
            return false;
        }
        LocalDateTime bufferStart = start.minusMinutes(rule.getBufferTimeMinutes());
        LocalDateTime bufferEnd = end.plusMinutes(rule.getBufferTimeMinutes());
        List<Booking> windowBookings = bookingRepository.findConfirmedInWindow(
                resource.getId(), bufferStart, bufferEnd);
        if (windowBookings != null) {
            List<Booking> relevantWindowBookings = filterOutCurrentBooking(currentBooking, windowBookings);
            return relevantWindowBookings.stream()
                    .anyMatch(b -> directOverlaps == null || !directOverlaps.contains(b));
        }
        return false;
    }

    private void validateBufferTime(LabResource resource, LocalDateTime start, LocalDateTime end, List<Booking> directOverlaps) {
        if (checkBufferViolation(resource, start, end, directOverlaps, null)) {
            BookingRule rule = resource.getBookingRule();
            throw new BookingRejectedException(BookingRejectionReason.BUFFER_TIME_VIOLATION,
                    "Booking violates the required buffer time of " + rule.getBufferTimeMinutes() + " minutes between sessions.");
        }
    }

    @Transactional(readOnly = true)
    public List<Booking> history(String ownerEmail) {
        UUID memberId = resolveMemberId(ownerEmail);
        return bookingRepository.findByMemberIdOrderByStartTimeDesc(memberId);
    }
    private UUID resolveMemberId(String email) {
        if (email == null) {
            return null;
        }
        if (memberRepository != null) {
            var memberOpt = memberRepository.findByEmail(email);
            if (memberOpt.isPresent()) {
                return memberOpt.get().getId();
            }
        }
        return UUID.nameUUIDFromBytes(email.getBytes());
    }

    private boolean isBookingOwner(Booking booking, UUID memberId, String ownerEmail) {
        if (booking.getMemberId() != null && memberId != null) {
            return booking.getMemberId().equals(memberId);
        }
        return booking.getOwnerEmail() != null && booking.getOwnerEmail().equalsIgnoreCase(ownerEmail);
    }
}
