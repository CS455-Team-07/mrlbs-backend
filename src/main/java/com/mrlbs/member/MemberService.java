package com.mrlbs.member;

import com.mrlbs.booking.credit.CreditAccount;
import com.mrlbs.booking.credit.CreditAccountRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Service
public class MemberService {
    private static final BigDecimal INITIAL_CREDITS = new BigDecimal("1000");

    private final MemberRepository memberRepository;
    private final CreditAccountRepository creditAccountRepository;

    public MemberService(MemberRepository memberRepository, CreditAccountRepository creditAccountRepository) {
        this.memberRepository = memberRepository;
        this.creditAccountRepository = creditAccountRepository;
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

        return new MemberResponse(
                member.getId(),
                member.getEmail(),
                member.getName(),
                member.getRole(),
                account.getBalance(),
                member.getCreatedAt()
        );
    }
}
