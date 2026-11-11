package com.mrlbs.member;

import com.mrlbs.booking.credit.CreditAccount;
import com.mrlbs.booking.credit.CreditAccountRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Service
public class MemberService {
    private static final BigDecimal INITIAL_CREDITS = new BigDecimal("1000");

    private final MemberRepository memberRepository;
    private final CreditAccountRepository creditAccountRepository;
    private final com.mrlbs.booking.BookingRepository bookingRepository;

    @org.springframework.beans.factory.annotation.Autowired
    public MemberService(MemberRepository memberRepository,
                         CreditAccountRepository creditAccountRepository,
                         @org.springframework.beans.factory.annotation.Autowired(required = false)
                         com.mrlbs.booking.BookingRepository bookingRepository) {
        this.memberRepository = memberRepository;
        this.creditAccountRepository = creditAccountRepository;
        this.bookingRepository = bookingRepository;
    }

    public MemberService(MemberRepository memberRepository, CreditAccountRepository creditAccountRepository) {
        this(memberRepository, creditAccountRepository, null);
    }

    public static LocalDateTime calculateCurrentCycleStart(LocalDateTime createdAt, LocalDateTime now) {
        if (createdAt == null || now == null || now.isBefore(createdAt)) {
            return createdAt != null ? createdAt : now;
        }
        long daysBetween = java.time.temporal.ChronoUnit.DAYS.between(createdAt, now);
        long completedWeeks = daysBetween >= 0 ? daysBetween / 7 : 0;
        return createdAt.plusDays(completedWeeks * 7);
    }

    @Transactional
    public BigDecimal syncRollingWeeklyCredits(String email) {
        if (email == null || email.isBlank()) {
            return BigDecimal.ZERO;
        }
        String cleanEmail = email.trim().toLowerCase();
        Member member = memberRepository.findByEmail(cleanEmail).orElse(null);
        CreditAccount account = creditAccountRepository.findById(cleanEmail).orElse(null);
        if (member == null || account == null) {
            return account != null ? account.getBalance() : BigDecimal.ZERO;
        }
        if (member.getCreatedAt() == null || bookingRepository == null) {
            return account.getBalance();
        }

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime cycleStart = calculateCurrentCycleStart(member.getCreatedAt(), now);

        List<com.mrlbs.booking.Booking> memberBookings = bookingRepository.findByMemberIdOrderByStartTimeDesc(member.getId());
        BigDecimal spentThisCycle = BigDecimal.ZERO;
        if (memberBookings != null) {
            for (com.mrlbs.booking.Booking b : memberBookings) {
                if (b == null) continue;
                LocalDateTime bookingCreation = b.getCreatedAt() != null ? b.getCreatedAt() : b.getStartTime();
                if (bookingCreation != null && !bookingCreation.isBefore(cycleStart)) {
                    if (b.getStatus() != com.mrlbs.booking.BookingStatus.CANCELLED
                            && b.getStatus() != com.mrlbs.booking.BookingStatus.REJECTED) {
                        BigDecimal cost = b.getCreditCost();
                        if (cost != null && cost.signum() > 0) {
                            spentThisCycle = spentThisCycle.add(cost);
                        }
                    }
                }
            }
        }

        BigDecimal expectedBalance = INITIAL_CREDITS.subtract(spentThisCycle).max(BigDecimal.ZERO);
        if (account.getBalance().compareTo(expectedBalance) != 0) {
            account.setBalance(expectedBalance);
            creditAccountRepository.save(account);
        }
        return expectedBalance;
    }

    @org.springframework.scheduling.annotation.Scheduled(cron = "0 0 * * * *")
    @Transactional
    public void scheduledRollingWeeklyCreditsSweep() {
        if (memberRepository == null) return;
        try {
            for (Member m : memberRepository.findAll()) {
                if (m.getEmail() != null) {
                    syncRollingWeeklyCredits(m.getEmail());
                }
            }
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(MemberService.class).warn("Error during rolling weekly credit sweep: {}", e.getMessage());
        }
    }

    @Transactional(readOnly = true)
    public boolean isMemberRegistered(String rawEmail) {
        if (rawEmail == null || rawEmail.isBlank()) return false;
        return memberRepository.findByEmail(rawEmail.trim().toLowerCase()).isPresent();
    }

    @Transactional
    public MemberResponse loginOrCreateMember(String rawEmail, String rawName) {
        if (rawEmail == null || rawEmail.isBlank()) {
            throw new IllegalArgumentException("Member email cannot be empty.");
        }
        String email = rawEmail.trim().toLowerCase();
        String name = (rawName != null && !rawName.isBlank()) ? rawName.trim() : email.split("@")[0];

        Member member = memberRepository.findByEmail(email).orElseGet(() -> {
            Member newMember = new Member(email, name, "MEMBER");
            return memberRepository.save(newMember);
        });

        // Initialize credit account with 1000 credits on first registration if not already present
        CreditAccount account = creditAccountRepository.findById(email).orElseGet(() -> {
            CreditAccount newAccount = new CreditAccount(email, INITIAL_CREDITS);
            return creditAccountRepository.save(newAccount);
        });

        BigDecimal balance = syncRollingWeeklyCredits(email);

        return new MemberResponse(
                member.getId(),
                member.getEmail(),
                member.getName(),
                member.getRole(),
                balance,
                member.getCreatedAt()
        );
    }
}
