package com.mrlbs.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class OtpService {

    private static final int CODE_LENGTH = 6;
    private static final long TTL_SECONDS = 300; // 5 minutes

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
        this.institutionalDomain = institutionalDomain.trim().toLowerCase();
    }

    /**
     * Generates a 6-digit OTP, stores it, and emails it to the given address.
     * 
     * @throws IllegalArgumentException if the email is not from the institutional
     *                                  domain.
     */
    public void generateAndSend(String rawEmail) {
        String email = rawEmail.trim().toLowerCase();
        if (!email.endsWith("@" + institutionalDomain)) {
            throw new IllegalArgumentException(
                    "Only @" + institutionalDomain + " email addresses are accepted.");
        }

        String code = generateCode();
        OtpEntry entry = new OtpEntry(code, Instant.now().plusSeconds(TTL_SECONDS));
        store.put(email, entry);

        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(fromAddress);
            message.setTo(email);
            message.setSubject("MRLBS – Your verification code");
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
        String email = rawEmail.trim().toLowerCase();
        OtpEntry entry = store.get(email);
        if (entry == null)
            return false;
        if (!Instant.now().isBefore(entry.expiresAt())) {
            store.remove(email, entry);
            return false;
        }
        return entry.code().equals(code.trim()) && store.remove(email, entry);
    }

    private String generateCode() {
        SecureRandom random = new SecureRandom();
        StringBuilder sb = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            sb.append(random.nextInt(10));
        }
        return sb.toString();
    }

    private record OtpEntry(String code, Instant expiresAt) {
    }
}
