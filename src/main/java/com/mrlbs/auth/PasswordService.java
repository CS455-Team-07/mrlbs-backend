package com.mrlbs.auth;

import com.mrlbs.member.Member;
import com.mrlbs.member.MemberRepository;
import com.mrlbs.member.MemberService;
import com.mrlbs.security.AdminResourceScope;
import com.mrlbs.security.PasswordPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Service
public class PasswordService {
    private final MemberRepository members;
    private final MemberService memberService;
    private final PasswordEncoder encoder;
    private final OtpService otp;
    private final String dummyHash;

    public PasswordService(MemberRepository members, MemberService memberService, PasswordEncoder encoder, OtpService otp) {
        this.members = members;
        this.memberService = memberService;
        this.encoder = encoder;
        this.otp = otp;
        this.dummyHash = encoder.encode(UUID.randomUUID().toString());
    }

    @Transactional(readOnly = true)
    public Optional<PasswordPrincipal> authenticate(String email, String password) {
        var member = members.findByEmail(email);
        var hash = member.map(Member::getPasswordHash);
        if (encoder.matches(password, hash.orElse(dummyHash)) && hash.isPresent()) {
            return member.map(PasswordPrincipal::new);
        }
        return Optional.empty();
    }

    @Transactional
    public boolean resetPassword(String email, String code, String password) {
        if (!otp.verify(email, code)) {
            return false;
        }
        Member member;
        if (AdminResourceScope.isScopedAdmin(email)) {
            member = members.findByEmail(email).orElseGet(() ->
                    members.save(new Member(email, email.substring(0, email.indexOf('@')), "ADMIN")));
            member.setRole("ADMIN");
        } else {
            var profile = memberService.loginOrCreateMember(email, null);
            member = members.findById(profile.id()).orElseThrow();
        }
        member.replacePassword(encoder.encode(password));
        return true;
    }
}
