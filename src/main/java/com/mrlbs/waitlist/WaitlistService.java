package com.mrlbs.waitlist;

import com.mrlbs.booking.Booking;
import com.mrlbs.booking.BookingRepository;
import com.mrlbs.booking.BookingStatus;
import com.mrlbs.member.MemberRepository;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@Service
public class WaitlistService {
    private final WaitlistEntryRepository waitlistRepository;
    private final MemberRepository memberRepository;
    private final BookingRepository bookingRepository;
    private final com.mrlbs.booking.credit.CreditAccountRepository creditAccountRepository;

    public WaitlistService(WaitlistEntryRepository waitlistRepository,
                           MemberRepository memberRepository,
                           BookingRepository bookingRepository) {
        this(waitlistRepository, memberRepository, bookingRepository, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public WaitlistService(WaitlistEntryRepository waitlistRepository,
                           MemberRepository memberRepository,
                           BookingRepository bookingRepository,
                           @org.springframework.beans.factory.annotation.Autowired(required = false)
                           com.mrlbs.booking.credit.CreditAccountRepository creditAccountRepository) {
        this.waitlistRepository = waitlistRepository;
        this.memberRepository = memberRepository;
        this.bookingRepository = bookingRepository;
        this.creditAccountRepository = creditAccountRepository;
    }

    public long calculateQueuePosition(WaitlistEntry target) {
        if (target == null || target.getStatus() != BookingStatus.IN_PROGRESS) {
            return 0;
        }
        UUID resId = target.getResource() != null ? target.getResource().getId() : null;
        if (resId == null) {
            return 0;
        }

        List<WaitlistEntry> activeQueue = new ArrayList<>(waitlistRepository.findActiveQueue(
                resId, target.getStartTime(), target.getEndTime()));

        activeQueue.sort(Comparator
                .comparing(WaitlistEntry::getJoinedAt, Comparator.nullsLast(LocalDateTime::compareTo))
                .thenComparing(entry -> entry.getId() != null ? entry.getId().toString() : ""));

        for (int i = 0; i < activeQueue.size(); i++) {
            if (activeQueue.get(i).getId() != null && activeQueue.get(i).getId().equals(target.getId())) {
                return i + 1;
            }
        }
        return activeQueue.size() + 1;
    }

    public long getNextQueuePosition(UUID resourceId, LocalDateTime startTime, LocalDateTime endTime) {
        LocalDateTime[] slot = resolveWaitlistSlot(resourceId, startTime, endTime);
        List<WaitlistEntry> activeQueue = waitlistRepository.findActiveQueue(resourceId, slot[0], slot[1]);
        return activeQueue.size() + 1;
    }

    public LocalDateTime[] resolveWaitlistSlot(UUID resourceId, LocalDateTime startTime, LocalDateTime endTime) {
        if (resourceId == null || startTime == null || endTime == null || bookingRepository == null) {
            return new LocalDateTime[]{startTime, endTime};
        }
        List<Booking> overlapping = bookingRepository.findOverlappingConfirmed(resourceId, startTime, endTime);
        if (overlapping != null) {
            for (Booking b : overlapping) {
                if (b != null && b.getStartTime() != null && b.getEndTime() != null
                        && b.getStatus() == BookingStatus.CONFIRMED) {
                    if (!startTime.isBefore(b.getStartTime()) && !endTime.isAfter(b.getEndTime())) {
                        return new LocalDateTime[]{b.getStartTime(), b.getEndTime()};
                    }
                }
            }
        }
        return new LocalDateTime[]{startTime, endTime};
    }

    @Transactional(readOnly = true)
    public List<WaitlistResponse> getMemberWaitlist(String memberEmail) {
        UUID memberId = resolveMemberId(memberEmail);
        List<WaitlistEntry> entries = waitlistRepository.findByMemberIdOrderByJoinedAtDesc(memberId);
        return entries.stream()
                .map(entry -> {
                    Long pos = entry.getStatus() == BookingStatus.IN_PROGRESS ? calculateQueuePosition(entry) : null;
                    return WaitlistResponse.from(entry, pos);
                })
                .toList();
    }

    @Transactional
    public WaitlistResponse leaveWaitlist(String memberEmail, UUID waitlistEntryId) {
        UUID memberId = resolveMemberId(memberEmail);
        WaitlistEntry entry = waitlistRepository.findById(waitlistEntryId)
                .orElseThrow(() -> new IllegalArgumentException("Waitlist entry not found"));

        if (!entry.getMemberId().equals(memberId)) {
            throw new AccessDeniedException("You do not have permission to leave this waitlist entry");
        }

        if (entry.getStatus() == BookingStatus.IN_PROGRESS) {
            entry.setStatus(BookingStatus.CANCELLED);
            entry.setLeftAt(LocalDateTime.now());
            waitlistRepository.save(entry);

            if (entry.getBookingId() != null) {
                bookingRepository.findById(entry.getBookingId()).ifPresent(booking -> {
                    if (booking.getStatus() == BookingStatus.IN_PROGRESS) {
                        booking.setStatus(BookingStatus.CANCELLED);
                        booking.setCancelledAt(LocalDateTime.now());
                        bookingRepository.save(booking);

                        if (booking.getCreditCost() != null && booking.getCreditCost().signum() > 0 && creditAccountRepository != null) {
                            creditAccountRepository.findForUpdate(memberEmail).ifPresent(account -> {
                                account.credit(booking.getCreditCost());
                                creditAccountRepository.save(account);
                            });
                        }
                    }
                });
            }
        }

        return WaitlistResponse.from(entry, null);
    }

    public UUID resolveMemberId(String email) {
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
}
