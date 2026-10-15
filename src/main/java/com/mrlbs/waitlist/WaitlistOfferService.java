package com.mrlbs.waitlist;

import com.mrlbs.booking.Booking;
import com.mrlbs.booking.BookingRejectedException;
import com.mrlbs.booking.BookingRejectionReason;
import com.mrlbs.booking.BookingRepository;
import com.mrlbs.booking.BookingStatus;
import com.mrlbs.booking.credit.CreditAccount;
import com.mrlbs.booking.credit.CreditAccountRepository;
import com.mrlbs.member.Member;
import com.mrlbs.member.MemberRepository;
import com.mrlbs.notification.InAppNotification;
import com.mrlbs.notification.InAppNotificationRepository;
import com.mrlbs.resource.BookingPolicy;
import com.mrlbs.resource.BookingRule;
import com.mrlbs.resource.LabResource;
import com.mrlbs.resource.LabResourceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class WaitlistOfferService {

    private static final Logger log = LoggerFactory.getLogger(WaitlistOfferService.class);

    private final WaitlistOfferRepository offerRepository;
    private final WaitlistEntryRepository waitlistEntryRepository;
    private final BookingRepository bookingRepository;
    private final LabResourceRepository resourceRepository;
    private final CreditAccountRepository creditAccountRepository;
    private final MemberRepository memberRepository;
    private final InAppNotificationRepository notificationRepository;

    @org.springframework.beans.factory.annotation.Value("${mrlbs.waitlist.offers.enabled:true}")
    private boolean offerModeEnabled = true;

    public boolean isOfferModeEnabled() {
        return offerModeEnabled;
    }

    public void setOfferModeEnabled(boolean offerModeEnabled) {
        this.offerModeEnabled = offerModeEnabled;
    }

    public WaitlistOfferService(WaitlistOfferRepository offerRepository,
                                WaitlistEntryRepository waitlistEntryRepository,
                                BookingRepository bookingRepository,
                                LabResourceRepository resourceRepository,
                                CreditAccountRepository creditAccountRepository,
                                MemberRepository memberRepository,
                                InAppNotificationRepository notificationRepository) {
        this.offerRepository = offerRepository;
        this.waitlistEntryRepository = waitlistEntryRepository;
        this.bookingRepository = bookingRepository;
        this.resourceRepository = resourceRepository;
        this.creditAccountRepository = creditAccountRepository;
        this.memberRepository = memberRepository;
        this.notificationRepository = notificationRepository;
    }

    /**
     * Evaluates a released slot to create exactly one active offer for the first eligible FCFS waitlist member.
     */
    @Transactional
    public Optional<WaitlistOffer> processReleasedSlotForOffer(UUID resourceId, LocalDateTime releasedStart, LocalDateTime releasedEnd) {
        if (resourceId == null || releasedStart == null || releasedEnd == null || !releasedEnd.isAfter(releasedStart)) {
            return Optional.empty();
        }

        // Clean up any pending offers that have expired so they release the slot
        processExpiredOffers();

        LocalDateTime now = LocalDateTime.now();

        // 1. Ensure only one active offer exists for the same released slot at a time
        List<WaitlistOffer> activeOffers = offerRepository.findActiveOffersForSlot(resourceId, releasedStart, releasedEnd, now);
        if (!activeOffers.isEmpty()) {
            log.debug("An active offer already exists for slot [{}, {}] on resource {}", releasedStart, releasedEnd, resourceId);
            return Optional.empty();
        }

        // Lock resource
        LabResource resource = null;
        try {
            resource = resourceRepository.findByIdForUpdate(resourceId)
                    .orElseGet(() -> resourceRepository.findById(resourceId).orElse(null));
        } catch (Exception e) {
            resource = resourceRepository.findById(resourceId).orElse(null);
        }

        if (resource == null || !resource.isActive()) {
            return Optional.empty();
        }

        List<WaitlistEntry> candidates = null;
        try {
            candidates = waitlistEntryRepository.findCandidatesOverlappingReleasedSlotForUpdate(
                    resourceId, releasedStart, releasedEnd);
        } catch (Exception e) {
            candidates = waitlistEntryRepository.findCandidatesOverlappingReleasedSlot(
                    resourceId, releasedStart, releasedEnd);
        }

        if (candidates == null || candidates.isEmpty()) {
            return Optional.empty();
        }

        for (WaitlistEntry candidate : candidates) {
            if (candidate.getStatus() != BookingStatus.IN_PROGRESS) {
                continue;
            }

            if (candidate.getBookingId() == null) {
                continue;
            }

            Booking booking = bookingRepository.findById(candidate.getBookingId()).orElse(null);
            if (booking == null || booking.getStatus() != BookingStatus.IN_PROGRESS) {
                continue;
            }

            LocalDateTime requestedStart = booking.getStartTime();
            LocalDateTime requestedEnd = booking.getEndTime();

            // Window containment check
            if (requestedStart.isBefore(releasedStart) || requestedEnd.isAfter(releasedEnd)) {
                continue;
            }

            // Duration & rules
            if (!isValidDuration(resource, requestedStart, requestedEnd)) {
                continue;
            }

            // Quota
            if (!hasAvailableQuota(candidate.getMemberId(), resource)) {
                continue;
            }

            // Check availability against other confirmed bookings
            List<Booking> overlaps = bookingRepository.findOverlappingConfirmedForUpdate(resource.getId(), requestedStart, requestedEnd);
            List<Booking> blockingOverlaps = (overlaps != null ? overlaps : List.<Booking>of()).stream()
                    .filter(b -> b != null && !b.getId().equals(booking.getId()))
                    .filter(b -> b.getStatus() == BookingStatus.CONFIRMED || b.getStatus() == BookingStatus.PENDING_APPROVAL)
                    .toList();

            if (!blockingOverlaps.isEmpty()) {
                continue;
            }

            // Buffer time
            if (violatesBufferTime(resource, requestedStart, requestedEnd, blockingOverlaps, booking)) {
                continue;
            }

            // Member credit balance against preserved cost
            BigDecimal preservedCost = candidate.getCreditCost() != null ? candidate.getCreditCost() : BigDecimal.ZERO;
            String memberEmail = resolveMemberEmail(booking, candidate.getMemberId());
            if (memberEmail == null) {
                continue;
            }

            // Eligible candidate found! Create offer
            WaitlistOffer offer = createOffer(candidate);
            return Optional.of(offer);
        }

        return Optional.empty();
    }

    /**
     * Creates a waitlist offer for an entry with an exact 5-minute expiry, updates entry to OFFERED,
     * and sends an in-app notification.
     */
    @Transactional
    public WaitlistOffer createOffer(WaitlistEntry entry) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime expiresAt = now.plusMinutes(5);

        WaitlistOffer offer = new WaitlistOffer(entry, "PENDING", expiresAt);
        offer.setOfferedAt(now);
        WaitlistOffer savedOffer = offerRepository.save(offer);

        entry.setStatus(BookingStatus.IN_PROGRESS);
        waitlistEntryRepository.save(entry);

        // Send in-app notification
        String resourceName = entry.getResource() != null ? entry.getResource().getName() : "Requested Lab";
        String timeStr = entry.getStartTime().toString().replace('T', ' ').substring(0, 16) +
                " - " + entry.getEndTime().toString().replace('T', ' ').substring(11, 16);
        String title = "Slot Available: " + resourceName;
        String message = "A requested slot for " + resourceName + " (" + timeStr +
                ") is now available! Confirm your booking within 5 minutes before the offer expires.";

        InAppNotification notification = new InAppNotification(entry.getMemberId(), title, message, savedOffer.getId());
        notificationRepository.save(notification);

        return savedOffer;
    }

    /**
     * Accepts an active offer. Validates ownership, expiry, availability, and credit balance,
     * updates the existing booking to CONFIRMED, and deducts credits exactly once.
     */
    @Transactional(noRollbackFor = IllegalStateException.class)
    public Booking acceptOffer(String memberEmail, UUID offerId) {
        UUID memberId = resolveMemberId(memberEmail);
        WaitlistOffer offer = null;
        try {
            offer = offerRepository.findByIdForUpdate(offerId)
                    .orElseGet(() -> offerRepository.findById(offerId).orElse(null));
        } catch (Exception e) {
            offer = offerRepository.findById(offerId).orElse(null);
        }
        if (offer == null) {
            throw new BookingRejectedException(BookingRejectionReason.RESOURCE_NOT_FOUND, "Offer was not found.");
        }

        WaitlistEntry entry = offer.getWaitlistEntry();
        if (entry == null || !entry.getMemberId().equals(memberId)) {
            throw new AccessDeniedException("You are not authorized to accept this offer.");
        }

        LocalDateTime now = LocalDateTime.now();

        // 1. Expiry check
        if (now.isAfter(offer.getExpiresAt()) || "EXPIRED".equalsIgnoreCase(offer.getStatus())) {
            offer.setStatus("EXPIRED");
            offerRepository.save(offer);
            throw new IllegalStateException("This offer has expired and can no longer be accepted.");
        }

        // 2. Status check
        if (!"PENDING".equalsIgnoreCase(offer.getStatus())) {
            throw new IllegalStateException("Offer is not in pending status (current status: " + offer.getStatus() + ").");
        }

        Booking booking = bookingRepository.findById(entry.getBookingId()).orElseThrow(() ->
                new BookingRejectedException(BookingRejectionReason.RESOURCE_NOT_FOUND, "Associated booking not found."));

        LabResource resource = entry.getResource();
        if (resource == null || !resource.isActive()) {
            throw new BookingRejectedException(BookingRejectionReason.RESOURCE_NOT_FOUND, "Resource is not active.");
        }

        // 3. Revalidate availability
        List<Booking> overlaps = bookingRepository.findOverlappingConfirmedForUpdate(
                resource.getId(), entry.getStartTime(), entry.getEndTime());
        boolean hasOverlap = (overlaps != null ? overlaps : List.<Booking>of()).stream()
                .filter(b -> b != null && !b.getId().equals(booking.getId()))
                .anyMatch(b -> b.getStatus() == BookingStatus.CONFIRMED || b.getStatus() == BookingStatus.PENDING_APPROVAL);

        if (hasOverlap) {
            throw new BookingRejectedException(BookingRejectionReason.SLOT_NO_LONGER_AVAILABLE, "The slot is no longer available.");
        }

        // 4. Validate member credit balance against PRESERVED cost
        BigDecimal preservedCost = entry.getCreditCost() != null ? entry.getCreditCost() : BigDecimal.ZERO;
        CreditAccount account = creditAccountRepository.findForUpdate(memberEmail)
                .orElseGet(() -> creditAccountRepository.findById(memberEmail).orElse(null));

        BigDecimal alreadyDeducted = booking.getCreditCost() != null ? booking.getCreditCost() : BigDecimal.ZERO;
        boolean alreadyPaid = alreadyDeducted.compareTo(preservedCost) >= 0 && alreadyDeducted.signum() > 0;

        if (!alreadyPaid && preservedCost.signum() > 0) {
            if (account == null || account.getBalance().compareTo(preservedCost) < 0) {
                throw new BookingRejectedException(BookingRejectionReason.INSUFFICIENT_CREDITS,
                        "The member does not have enough credits for this booking.");
            }
        }

        // 5. Deduct credits exactly once and update booking
        if (!alreadyPaid && preservedCost.signum() > 0 && account != null) {
            account.debit(preservedCost);
            creditAccountRepository.save(account);
        }

        booking.setStatus(BookingStatus.CONFIRMED);
        booking.setCreditCost(preservedCost);
        Booking confirmedBooking = bookingRepository.save(booking);

        // 6. Update waitlist entry and offer response
        entry.setStatus(BookingStatus.CONFIRMED);
        entry.setLeftAt(now);
        waitlistEntryRepository.save(entry);

        offer.setStatus("ACCEPTED");
        offer.setRespondedAt(now);
        offer.setResultingBookingId(confirmedBooking.getId());
        offerRepository.save(offer);

        // 7. Notify member of confirmed booking
        String resourceName = resource.getName() != null ? resource.getName() : "Requested Lab";
        String startStr = booking.getStartTime() != null ? booking.getStartTime().toString().replace('T', ' ').substring(0, 16) : "";
        String endStr = booking.getEndTime() != null ? booking.getEndTime().toString().replace('T', ' ').substring(11, 16) : "";
        InAppNotification confirmNotif = new InAppNotification(
                entry.getMemberId(),
                "Booking Confirmed: " + resourceName,
                "Your booking for " + resourceName + " (" + startStr + " to " + endStr + ") is now confirmed!",
                offer.getId()
        );
        notificationRepository.save(confirmNotif);

        return confirmedBooking;
    }

    /**
     * Declines an active offer. Records response time, marks offer DECLINED, entry LEFT, and booking CANCELLED,
     * and immediately triggers allocation for the next eligible member.
     */
    @Transactional
    public WaitlistOffer declineOffer(String memberEmail, UUID offerId) {
        UUID memberId = resolveMemberId(memberEmail);
        WaitlistOffer offer = null;
        try {
            offer = offerRepository.findByIdForUpdate(offerId)
                    .orElseGet(() -> offerRepository.findById(offerId).orElse(null));
        } catch (Exception e) {
            offer = offerRepository.findById(offerId).orElse(null);
        }
        if (offer == null) {
            throw new BookingRejectedException(BookingRejectionReason.RESOURCE_NOT_FOUND, "Offer was not found.");
        }

        WaitlistEntry entry = offer.getWaitlistEntry();
        if (entry == null || !entry.getMemberId().equals(memberId)) {
            throw new AccessDeniedException("You are not authorized to decline this offer.");
        }

        if (!"PENDING".equalsIgnoreCase(offer.getStatus())) {
            throw new IllegalStateException("Offer is not in pending status.");
        }

        LocalDateTime now = LocalDateTime.now();
        offer.setStatus("DECLINED");
        offer.setRespondedAt(now);
        WaitlistOffer savedOffer = offerRepository.save(offer);

        entry.setStatus(BookingStatus.CANCELLED);
        entry.setLeftAt(now);
        waitlistEntryRepository.save(entry);

        if (entry.getBookingId() != null) {
            bookingRepository.findById(entry.getBookingId()).ifPresent(b -> {
                if (b.getStatus() == BookingStatus.IN_PROGRESS) {
                    b.setStatus(BookingStatus.CANCELLED);
                    b.setCancelledAt(now);
                    bookingRepository.save(b);

                    if (b.getCreditCost() != null && b.getCreditCost().signum() > 0) {
                        creditAccountRepository.findForUpdate(memberEmail).ifPresent(account -> {
                            account.credit(b.getCreditCost());
                            creditAccountRepository.save(account);
                        });
                    }
                }
            });
        }

        // Immediately trigger allocation for the next eligible waitlist member
        if (entry.getResource() != null) {
            processReleasedSlotForOffer(entry.getResource().getId(), entry.getStartTime(), entry.getEndTime());
        }

        return savedOffer;
    }

    /**
     * Recover and process any missed expirations upon application startup.
     */
    @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    @Transactional
    public void onApplicationReady() {
        try {
            processExpiredOffers();
        } catch (Exception e) {
            log.warn("Could not process expired offers on application startup: {}", e.getMessage());
        }
    }

    /**
     * Finds and processes any offers that have exceeded their 5-minute expiry window,
     * marking them EXPIRED and triggering allocation for the next eligible waitlist member.
     */
    @org.springframework.scheduling.annotation.Scheduled(fixedRate = 5000)
    @Transactional
    public List<WaitlistOffer> processExpiredOffers() {
        LocalDateTime now = LocalDateTime.now();
        List<WaitlistOffer> expiredOffers = offerRepository.findExpiredPendingOffers(now);
        for (WaitlistOffer offer : expiredOffers) {
            offer.setStatus("EXPIRED");
            offerRepository.save(offer);

            WaitlistEntry entry = offer.getWaitlistEntry();
            if (entry != null && entry.getStatus() == BookingStatus.IN_PROGRESS) {
                entry.setStatus(BookingStatus.CANCELLED);
                entry.setLeftAt(now);
                waitlistEntryRepository.save(entry);

                if (entry.getBookingId() != null) {
                    bookingRepository.findById(entry.getBookingId()).ifPresent(b -> {
                        if (b.getStatus() == BookingStatus.IN_PROGRESS) {
                            b.setStatus(BookingStatus.CANCELLED);
                            b.setCancelledAt(now);
                            bookingRepository.save(b);

                            String memberEmail = resolveMemberEmail(b, entry.getMemberId());
                            if (memberEmail != null && b.getCreditCost() != null && b.getCreditCost().signum() > 0) {
                                creditAccountRepository.findForUpdate(memberEmail).ifPresent(account -> {
                                    account.credit(b.getCreditCost());
                                    creditAccountRepository.save(account);
                                });
                            }
                        }
                    });
                }

                // Re-run FIFO allocation to offer the slot to the next eligible member
                if (entry.getResource() != null) {
                    processReleasedSlotForOffer(entry.getResource().getId(), entry.getStartTime(), entry.getEndTime());
                }
            }
        }
        return expiredOffers;
    }

    @Transactional
    public List<WaitlistOfferResponse> getActiveOffersForMember(String memberEmail) {
        processExpiredOffers();
        UUID memberId = resolveMemberId(memberEmail);
        List<WaitlistOffer> offers = offerRepository.findActivePendingOffersForMember(memberId, LocalDateTime.now());
        return offers.stream().map(WaitlistOfferResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public List<WaitlistOfferResponse> getAllOffersForMember(String memberEmail) {
        UUID memberId = resolveMemberId(memberEmail);
        List<WaitlistOffer> offers = offerRepository.findByMemberId(memberId);
        return offers.stream().map(WaitlistOfferResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public List<InAppNotification> getNotificationsForMember(String memberEmail) {
        UUID memberId = resolveMemberId(memberEmail);
        return notificationRepository.findByMemberIdOrderByCreatedAtDesc(memberId);
    }

    @Transactional
    public void markNotificationRead(String memberEmail, UUID notificationId) {
        UUID memberId = resolveMemberId(memberEmail);
        notificationRepository.findById(notificationId).ifPresent(n -> {
            if (n.getMemberId().equals(memberId)) {
                n.setRead(true);
                notificationRepository.save(n);
            }
        });
    }

    private boolean isValidDuration(LabResource resource, LocalDateTime start, LocalDateTime end) {
        BookingRule rule = resource != null ? resource.getBookingRule() : null;
        if (rule == null) return true;
        long durationMinutes = Duration.between(start, end).toMinutes();
        if (rule.getMinDurationMinutes() != null && durationMinutes < rule.getMinDurationMinutes()) return false;
        if (rule.getMaxDurationMinutes() != null && durationMinutes > rule.getMaxDurationMinutes()) return false;
        return true;
    }

    private boolean hasAvailableQuota(UUID memberId, LabResource resource) {
        BookingRule rule = resource != null ? resource.getBookingRule() : null;
        if (rule == null || rule.getMaxBookingsPerMember() == null || rule.getMaxBookingsPerMember() <= 0) return true;
        long count = bookingRepository.countActiveBookingsForMember(
                memberId, resource.getId(), List.of(BookingStatus.CONFIRMED, BookingStatus.PENDING_APPROVAL));
        return count < rule.getMaxBookingsPerMember();
    }

    private boolean violatesBufferTime(LabResource resource, LocalDateTime start, LocalDateTime end,
                                       List<Booking> directOverlaps, Booking currentBooking) {
        BookingRule rule = resource != null ? resource.getBookingRule() : null;
        if (rule == null || rule.getBufferTimeMinutes() == null || rule.getBufferTimeMinutes() <= 0) return false;
        LocalDateTime bufferStart = start.minusMinutes(rule.getBufferTimeMinutes());
        LocalDateTime bufferEnd = end.plusMinutes(rule.getBufferTimeMinutes());
        List<Booking> windowBookings = bookingRepository.findConfirmedInWindow(resource.getId(), bufferStart, bufferEnd);
        if (windowBookings != null) {
            return windowBookings.stream()
                    .filter(b -> b != null && (currentBooking == null || currentBooking.getId() == null || !currentBooking.getId().equals(b.getId())))
                    .filter(b -> b.getStatus() == BookingStatus.CONFIRMED || b.getStatus() == BookingStatus.PENDING_APPROVAL)
                    .anyMatch(b -> directOverlaps == null || !directOverlaps.contains(b));
        }
        return false;
    }

    private UUID resolveMemberId(String email) {
        if (email == null) return null;
        if (memberRepository != null) {
            var m = memberRepository.findByEmail(email);
            if (m.isPresent()) return m.get().getId();
        }
        return UUID.nameUUIDFromBytes(email.getBytes());
    }

    private String resolveMemberEmail(Booking booking, UUID memberId) {
        if (booking != null && booking.getOwnerEmail() != null && !booking.getOwnerEmail().isBlank()) {
            return booking.getOwnerEmail();
        }
        if (memberId != null && memberRepository != null) {
            return memberRepository.findById(memberId).map(Member::getEmail).orElse(null);
        }
        return null;
    }
}
