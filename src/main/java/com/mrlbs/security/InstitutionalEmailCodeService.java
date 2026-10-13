package com.mrlbs.security;

import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class InstitutionalEmailCodeService {
    static final String CHALLENGE_SESSION_KEY = InstitutionalEmailCodeService.class.getName() + ".challenge";
    private static final int MAX_ATTEMPTS = 5;
    private static final Duration CODE_LIFETIME = Duration.ofMinutes(10);
    private static final Duration REQUEST_INTERVAL = Duration.ofSeconds(60);

    private final ObjectProvider<JavaMailSender> mailSenderProvider;
    private final String fromAddress;
    private final String domain;
    private final SecureRandom random = new SecureRandom();
    private final ConcurrentHashMap<String, Long> lastRequestByEmail = new ConcurrentHashMap<>();

    public InstitutionalEmailCodeService(
            ObjectProvider<JavaMailSender> mailSenderProvider,
            @Value("${mrlbs.auth.institutional-domain:iitk.ac.in}") String domain,
            @Value("${mrlbs.mail.from:}") String fromAddress) {
        this.mailSenderProvider = mailSenderProvider;
        this.domain = domain.trim().toLowerCase(Locale.ROOT);
        this.fromAddress = fromAddress.trim();
    }

    public void sendCode(String rawEmail, HttpSession session) {
        String email = normalizeInstitutionalEmail(rawEmail);
        long now = System.currentTimeMillis();
        Long lastRequest = lastRequestByEmail.putIfAbsent(email, now);
        if (lastRequest != null) {
            if (now - lastRequest < REQUEST_INTERVAL.toMillis()) {
                throw new IllegalStateException("A code was already sent recently. Please wait a minute.");
            }
            lastRequestByEmail.replace(email, lastRequest, now);
        }

        JavaMailSender mailSender = mailSenderProvider.getIfAvailable();
        if (mailSender == null || fromAddress.isBlank()) {
            lastRequestByEmail.remove(email, now);
            throw new IllegalStateException("Institutional email delivery is not configured on the server.");
        }

        String code = "%06d".formatted(random.nextInt(1_000_000));
        session.setAttribute(CHALLENGE_SESSION_KEY,
                new Challenge(email, digest(code), now + CODE_LIFETIME.toMillis(), 0));
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(fromAddress);
            message.setTo(email);
            message.setSubject("Your MRLBS sign-in code");
            message.setText("Your MRLBS verification code is " + code + ". It expires in 10 minutes. "
                    + "If you did not request this code, you can ignore this email.");
            mailSender.send(message);
        } catch (RuntimeException exception) {
            session.removeAttribute(CHALLENGE_SESSION_KEY);
            lastRequestByEmail.remove(email, now);
            throw new IllegalStateException("Could not send the verification email. Check the mail server settings.");
        }
    }

    public InstitutionalUserPrincipal verifyCode(String rawEmail, String rawCode, HttpSession session) {
        String email = normalizeInstitutionalEmail(rawEmail);
        Object stored = session.getAttribute(CHALLENGE_SESSION_KEY);
        if (!(stored instanceof Challenge challenge) || System.currentTimeMillis() > challenge.expiresAt()) {
            session.removeAttribute(CHALLENGE_SESSION_KEY);
            throw new IllegalArgumentException("That code has expired. Request a new one.");
        }
        if (!challenge.email().equals(email)) {
            throw new IllegalArgumentException("The code was sent to a different email address.");
        }
        if (challenge.attempts() >= MAX_ATTEMPTS) {
            session.removeAttribute(CHALLENGE_SESSION_KEY);
            throw new IllegalArgumentException("Too many incorrect attempts. Request a new code.");
        }

        String code = rawCode == null ? "" : rawCode.trim();
        if (!code.matches("\\d{6}") || !MessageDigest.isEqual(
                challenge.codeDigest().getBytes(StandardCharsets.US_ASCII),
                digest(code).getBytes(StandardCharsets.US_ASCII))) {
            if (challenge.attempts() + 1 >= MAX_ATTEMPTS) {
                session.removeAttribute(CHALLENGE_SESSION_KEY);
            } else {
                session.setAttribute(CHALLENGE_SESSION_KEY,
                        new Challenge(challenge.email(), challenge.codeDigest(), challenge.expiresAt(), challenge.attempts() + 1));
            }
            throw new IllegalArgumentException("The verification code is incorrect.");
        }

        session.removeAttribute(CHALLENGE_SESSION_KEY);
        String name = email.substring(0, email.indexOf('@'));
        var authorities = AdminResourceScope.isScopedAdmin(email)
                ? java.util.List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))
                : java.util.List.of(new SimpleGrantedAuthority("ROLE_MEMBER"));
        return new InstitutionalUserPrincipal("mail-" + email, name, email, authorities);
    }

    String normalizeInstitutionalEmail(String rawEmail) {
        String email = rawEmail == null ? "" : rawEmail.trim().toLowerCase(Locale.ROOT);
        if (!email.matches("^[a-z0-9.!#$%&'*+/=?^_`{|}~-]+@" + java.util.regex.Pattern.quote(domain) + "$")) {
            throw new IllegalArgumentException("Use an email address from the @" + domain + " domain.");
        }
        return email;
    }

    private String digest(String code) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(code.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("Could not process the verification code.", exception);
        }
    }

    private record Challenge(String email, String codeDigest, long expiresAt, int attempts) { }
}
