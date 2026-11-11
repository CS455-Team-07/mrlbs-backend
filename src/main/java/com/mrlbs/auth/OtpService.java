package com.mrlbs.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class OtpService {

    private static final int CODE_LENGTH = 6;
    private static final long TTL_SECONDS = 300; // 5 minutes
    private static final int MAX_ATTEMPTS = 5;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final JavaMailSender mailSender;
    private final String fromAddress;
    private final String institutionalDomain;

    /** In-memory store: email → OtpEntry. Replace with Redis/DB in production. */
    private final Map<String, OtpEntry> store = new ConcurrentHashMap<>();

    public OtpService(
            JavaMailSender mailSender,
            @Value("${mrlbs.mail.from:noreply@iitk.ac.in}") String fromAddress,
            @Value("${mrlbs.auth.institutional-domain:iitk.ac.in}") String institutionalDomain) {
        this.mailSender = mailSender;
        this.fromAddress = fromAddress;
        this.institutionalDomain = institutionalDomain.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Generates a 6-digit OTP, stores it, and emails it to the given address.
     * 
     * @throws IllegalArgumentException if the email is not from the institutional
     *                                  domain.
     */
    public void generateAndSend(String rawEmail) {
        String email = rawEmail.trim().toLowerCase(Locale.ROOT);
        if (!email.endsWith("@" + institutionalDomain)) {
            throw new IllegalArgumentException(
                    "Only @" + institutionalDomain + " email addresses are accepted.");
        }

        String code = generateCode();
        OtpEntry entry = new OtpEntry(code, Instant.now().plusSeconds(TTL_SECONDS), 0);
        store.put(email, entry);

        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(fromAddress);
            message.setTo(email);
            message.setSubject("MRLBS password setup or reset");
            message.setText("Your one-time verification code is: " + code
                    + "\n\nThis code expires in 5 minutes."
                    + "\nIf you did not request this, please ignore this email.");
            mailSender.send(message);
        } catch (MailException exception) {
            store.remove(email, entry);
            throw exception;
        }
    }

    /**
     * Validates the code for the given email.
     * 
     * @return true if the code matches and has not expired.
     */
    public boolean verify(String rawEmail, String code) {
        String email = rawEmail.trim().toLowerCase(Locale.ROOT);
        String suppliedCode = code.trim();
        boolean[] accepted = {false};
        store.computeIfPresent(email, (key, entry) -> {
            if (!Instant.now().isBefore(entry.expiresAt())) {
                return null;
            }
            accepted[0] = entry.code().equals(suppliedCode);
            if (accepted[0] || entry.failedAttempts() + 1 >= MAX_ATTEMPTS) {
                return null;
            }
            return new OtpEntry(entry.code(), entry.expiresAt(), entry.failedAttempts() + 1);
        });
        return accepted[0];
    }

    @Scheduled(fixedDelay = 60_000)
    void removeExpiredCodes() {
        Instant now = Instant.now();
        store.entrySet().removeIf(entry -> !now.isBefore(entry.getValue().expiresAt()));
    }

    private String generateCode() {
        StringBuilder sb = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            sb.append(RANDOM.nextInt(10));
        }
        return sb.toString();
    }

    private record OtpEntry(String code, Instant expiresAt, int failedAttempts) {
    }
}
