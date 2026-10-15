package com.mrlbs.waitlist;

import com.mrlbs.booking.Booking;
import com.mrlbs.booking.BookingRepository;
import com.mrlbs.booking.BookingStatus;
import com.mrlbs.booking.credit.CreditAccount;
import com.mrlbs.booking.credit.CreditAccountRepository;
import com.mrlbs.member.Member;
import com.mrlbs.member.MemberRepository;
import com.mrlbs.resource.BookingPolicy;
import com.mrlbs.resource.BookingRule;
import com.mrlbs.resource.LabResource;
import com.mrlbs.resource.LabResourceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Reusable backend service to evaluate released booking slots and perform FIFO waitlist allocations.
 */
@Service
public class WaitlistAllocationService {

    private static final Logger log = LoggerFactory.getLogger(WaitlistAllocationService.class);

    private final WaitlistEntryRepository waitlistEntryRepository;
    private final BookingRepository bookingRepository;
    private final LabResourceRepository resourceRepository;
    private final CreditAccountRepository creditAccountRepository;
    private final MemberRepository memberRepository;

    public WaitlistAllocationService(WaitlistEntryRepository waitlistEntryRepository,
                                   BookingRepository bookingRepository,
                                   LabResourceRepository resourceRepository,
                                   CreditAccountRepository creditAccountRepository,
                                   MemberRepository memberRepository) {
        this.waitlistEntryRepository = waitlistEntryRepository;
        this.bookingRepository = bookingRepository;
        this.resourceRepository = resourceRepository;
        this.creditAccountRepository = creditAccountRepository;
        this.memberRepository = memberRepository;
    }

    /**
     * Evaluates a released time slot for the given resource and allocates eligible waitlisted members in strict FIFO order.
     *
     * @param resourceId    ID of the resource
     * @param releasedStart Start time of the released window
     * @param releasedEnd   End time of the released window
     * @return List of bookings that were successfully allocated
     */
    @Transactional
    public List<Booking> processReleasedSlot(UUID resourceId, LocalDateTime releasedStart, LocalDateTime releasedEnd) {
        if (resourceId == null || releasedStart == null || releasedEnd == null || !releasedEnd.isAfter(releasedStart)) {
            return List.of();
        }

        // Lock resource to serialize allocation and prevent concurrent race conditions
        LabResource resource = null;
        try {
            resource = resourceRepository.findByIdForUpdate(resourceId)
                    .orElseGet(() -> resourceRepository.findById(resourceId).orElse(null));
        } catch (Exception e) {
            resource = resourceRepository.findById(resourceId).orElse(null);
        }

        if (resource == null || !resource.isActive()) {
            return List.of();
        }

        // Query active candidate entries in strict FIFO order: joinedAt ASC, id ASC
        List<WaitlistEntry> candidates = null;
        try {
            candidates = waitlistEntryRepository.findCandidatesOverlappingReleasedSlotForUpdate(
                    resourceId, releasedStart, releasedEnd);
        } catch (Exception e) {
            candidates = waitlistEntryRepository.findCandidatesOverlappingReleasedSlot(
                    resourceId, releasedStart, releasedEnd);
        }

        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }

        List<Booking> allocatedBookings = new ArrayList<>();

        for (WaitlistEntry candidate : candidates) {
            // 1. Skip inactive or already allocated/left entries
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

            // 2. Exact window containment check:
            // "Support requests fully contained within a larger previously booked window.
            // Allocate only when the entire requested window is available and passes booking rules,
            // duration limits, and buffer-time validation. Reject or skip partially unavailable windows."
            if (requestedStart.isBefore(releasedStart) || requestedEnd.isAfter(releasedEnd)) {
                log.debug("Skipping candidate {} because requested window [{}, {}] is not fully contained in released window [{}, {}]",
                        candidate.getId(), requestedStart, requestedEnd, releasedStart, releasedEnd);
                continue;
            }

            // 3. Duration limits & booking rules validation
            if (!isValidDuration(resource, requestedStart, requestedEnd)) {
                log.debug("Skipping candidate {} due to duration limits", candidate.getId());
                continue;
            }

            // 4. Member active booking quota validation
            if (!hasAvailableQuota(candidate.getMemberId(), resource)) {
                log.debug("Skipping candidate {} due to active quota limits", candidate.getId());
                continue;
            }

            // 5. Overlapping confirmed/pending booking check (against current database state)
            List<Booking> overlaps = bookingRepository.findOverlappingConfirmedForUpdate(resource.getId(), requestedStart, requestedEnd);
            List<Booking> blockingOverlaps = (overlaps != null ? overlaps : List.<Booking>of()).stream()
                    .filter(b -> b != null && !b.getId().equals(booking.getId()))
                    .filter(b -> b.getStatus() == BookingStatus.CONFIRMED || b.getStatus() == BookingStatus.PENDING_APPROVAL)
                    .toList();

            if (!blockingOverlaps.isEmpty()) {
                log.debug("Skipping candidate {} because time slot has blocking confirmed bookings", candidate.getId());
                continue;
            }

            // 6. Buffer time validation
            if (violatesBufferTime(resource, requestedStart, requestedEnd, blockingOverlaps, booking)) {
                log.debug("Skipping candidate {} due to buffer time violation", candidate.getId());
                continue;
            }

            // 7. Credit validation against preserved booking cost
            // "Preserve the original booking cost and M_contention calculated when the member joined the waitlist.
            // At allocation time, validate the member's current credit balance against that preserved cost.
            // Deduct credits exactly once, only when the booking becomes CONFIRMED."
            BigDecimal preservedCost = candidate.getCreditCost() != null ? candidate.getCreditCost() : BigDecimal.ZERO;
            String memberEmail = resolveMemberEmail(booking, candidate.getMemberId());
            if (memberEmail == null) {
                continue;
            }

            BigDecimal alreadyDeducted = booking.getCreditCost() != null ? booking.getCreditCost() : BigDecimal.ZERO;
            boolean alreadyPaid = alreadyDeducted.compareTo(preservedCost) >= 0 && alreadyDeducted.signum() > 0;

            CreditAccount account = creditAccountRepository.findForUpdate(memberEmail)
                    .orElseGet(() -> creditAccountRepository.findById(memberEmail).orElse(null));

            if (!alreadyPaid && (account == null || account.getBalance().compareTo(preservedCost) < 0)) {
                log.info("Candidate {} has insufficient credits (needs {}, has {}). Skipping to next eligible entry.",
                        candidate.getId(), preservedCost, account != null ? account.getBalance() : "none");
                continue;
            }

            // 8. Allocation execution:
            // Deduct credits exactly once, only if not already deducted at IN_PROGRESS
            if (!alreadyPaid && account != null) {
                account.debit(preservedCost);
                creditAccountRepository.save(account);
            }

            booking.setStatus(BookingStatus.CONFIRMED);
            booking.setCreditCost(preservedCost);
            Booking savedBooking = bookingRepository.save(booking);

            candidate.setStatus(BookingStatus.CONFIRMED);
            candidate.setLeftAt(LocalDateTime.now());
            waitlistEntryRepository.save(candidate);

            allocatedBookings.add(savedBooking);
        }

        return allocatedBookings;
    }

    private boolean isValidDuration(LabResource resource, LocalDateTime start, LocalDateTime end) {
        BookingRule rule = resource != null ? resource.getBookingRule() : null;
        if (rule == null) {
            return true;
        }
        long durationMinutes = Duration.between(start, end).toMinutes();
        if (rule.getMinDurationMinutes() != null && durationMinutes < rule.getMinDurationMinutes()) {
            return false;
        }
        if (rule.getMaxDurationMinutes() != null && durationMinutes > rule.getMaxDurationMinutes()) {
            return false;
        }
        return true;
    }

    private boolean hasAvailableQuota(UUID memberId, LabResource resource) {
        BookingRule rule = resource != null ? resource.getBookingRule() : null;
        if (rule == null || rule.getMaxBookingsPerMember() == null || rule.getMaxBookingsPerMember() <= 0) {
            return true;
        }
        // Count CONFIRMED and PENDING_APPROVAL bookings (excluding this member's IN_PROGRESS waitlist booking)
        long count = bookingRepository.countActiveBookingsForMember(
                memberId, resource.getId(), List.of(BookingStatus.CONFIRMED, BookingStatus.PENDING_APPROVAL));
        return count < rule.getMaxBookingsPerMember();
    }

    private boolean violatesBufferTime(LabResource resource, LocalDateTime start, LocalDateTime end,
                                       List<Booking> directOverlaps, Booking currentBooking) {
        BookingRule rule = resource != null ? resource.getBookingRule() : null;
        if (rule == null || rule.getBufferTimeMinutes() == null || rule.getBufferTimeMinutes() <= 0) {
            return false;
        }
        LocalDateTime bufferStart = start.minusMinutes(rule.getBufferTimeMinutes());
        LocalDateTime bufferEnd = end.plusMinutes(rule.getBufferTimeMinutes());
        List<Booking> windowBookings = bookingRepository.findConfirmedInWindow(
                resource.getId(), bufferStart, bufferEnd);
        if (windowBookings != null) {
            return windowBookings.stream()
                    .filter(b -> b != null && (currentBooking == null || currentBooking.getId() == null || !currentBooking.getId().equals(b.getId())))
                    .filter(b -> b.getStatus() == BookingStatus.CONFIRMED || b.getStatus() == BookingStatus.PENDING_APPROVAL)
                    .anyMatch(b -> directOverlaps == null || !directOverlaps.contains(b));
        }
        return false;
    }

    public String resolveMemberEmail(Booking booking, UUID memberId) {
        if (booking != null && booking.getOwnerEmail() != null && !booking.getOwnerEmail().isBlank()) {
            return booking.getOwnerEmail();
        }
        if (memberId != null && memberRepository != null) {
            return memberRepository.findById(memberId)
                    .map(Member::getEmail)
                    .orElse(null);
        }
        return null;
    }
}
