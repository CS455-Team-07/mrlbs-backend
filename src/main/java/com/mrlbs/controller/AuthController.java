package com.mrlbs.controller;

import com.mrlbs.ai.AiRateLimiter;
import com.mrlbs.auth.OtpService;
import com.mrlbs.auth.PasswordService;
import com.mrlbs.member.MemberService;
import com.mrlbs.security.AdminResourceScope;
import com.mrlbs.security.PasswordPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mail.MailException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.util.Locale;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private static final Logger log = LoggerFactory.getLogger(AuthController.class);
    private static final Duration RATE_WINDOW = Duration.ofMinutes(5);
    private static final String IITK_EMAIL = "^[A-Za-z0-9._%+-]+@iitk\\.ac\\.in$";

    private final PasswordService passwords;
    private final OtpService otp;
    private final MemberService members;
    private final AiRateLimiter rateLimiter;
    private final HttpSessionSecurityContextRepository contexts;
    private final HttpSessionCsrfTokenRepository csrfTokens;

    public AuthController(PasswordService passwords, OtpService otp, MemberService members,
            AiRateLimiter rateLimiter, HttpSessionSecurityContextRepository contexts,
            HttpSessionCsrfTokenRepository csrfTokens) {
        this.passwords = passwords;
        this.otp = otp;
        this.members = members;
        this.rateLimiter = rateLimiter;
        this.contexts = contexts;
        this.csrfTokens = csrfTokens;
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest body,
            HttpServletRequest request, HttpServletResponse response) {
        String email = normalize(body.email());
        limit("login", email, request, 15, 60);
        var principal = passwords.authenticate(email, body.password());
        if (principal.isEmpty()) {
            return ResponseEntity.status(401).body(Map.of("message", "Email or password is incorrect."));
        }

        request.getSession();
        request.changeSessionId();
        csrfTokens.saveToken(null, request, response);
        var user = principal.get();
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(user, null, user.getAuthorities()));
        SecurityContextHolder.setContext(context);
        contexts.saveContext(context, request, response);
        return ResponseEntity.ok(identity(user));
    }

    @PostMapping("/forgot-password")
    public ResponseEntity<?> forgotPassword(@Valid @RequestBody EmailRequest body, HttpServletRequest request) {
        String email = normalize(body.email());
        limit("otp-send", email, request, 3, 15);
        if (AdminResourceScope.isScopedAdmin(email)) {
            return ResponseEntity.badRequest().body(Map.of(
                    "message", "Contact the system operator for help with this administrator account."));
        }
        try {
            otp.generateAndSend(email);
            return ResponseEntity.noContent().build();
        } catch (MailException e) {
            log.warn("Password code delivery failed ({})", e.getClass().getSimpleName());
            return ResponseEntity.status(503).body(Map.of(
                    "message", "Could not send the verification code. Please try again."));
        }
    }

    @PostMapping("/reset-password")
    public ResponseEntity<?> resetPassword(@Valid @RequestBody ResetRequest body, HttpServletRequest request) {
        String email = normalize(body.email());
        limit("otp-verify", email, request, 15, 60);
        if (!passwords.resetPassword(email, body.code(), body.password())) {
            return ResponseEntity.status(400).body(Map.of(
                    "message", "The code is invalid or expired. Request a new code if needed."));
        }
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me")
    public Identity currentUser(@AuthenticationPrincipal PasswordPrincipal user) {
        return identity(user);
    }

    @GetMapping("/member-credits")
    @PreAuthorize("hasRole('MEMBER')")
    public Map<String, Object> memberCredits(@AuthenticationPrincipal PasswordPrincipal user) {
        return Map.of("creditBalance", members.syncRollingWeeklyCredits(user.getEmail()));
    }

    @GetMapping("/csrf")
    public CsrfTokenResponse csrfToken(CsrfToken token) {
        return new CsrfTokenResponse(token.getToken(), token.getHeaderName());
    }

    private void limit(String action, String email, HttpServletRequest request, int perEmail, int perIp) {
        if (!rateLimiter.tryAcquire("auth:" + action + ":ip:" + request.getRemoteAddr(), perIp, RATE_WINDOW)
                || !rateLimiter.tryAcquire("auth:" + action + ":email:" + email, perEmail, RATE_WINDOW)) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS);
        }
    }

    private static Identity identity(PasswordPrincipal user) {
        String scope = AdminResourceScope.scopeForEmail(user.getEmail()).orElse(null);
        return new Identity(user.getSubject(), user.getFullName(), user.getEmail(),
                scope == null ? "member" : "admin", scope);
    }

    private static String normalize(String email) {
        return email.toLowerCase(Locale.ROOT);
    }

    public record Identity(String id, String name, String email, String role, String resourceTypeScope) {}
    public record CsrfTokenResponse(String token, String headerName) {}

    public record LoginRequest(
            @NotBlank @Email @Size(max = 254) @Pattern(regexp = IITK_EMAIL, flags = Pattern.Flag.CASE_INSENSITIVE)
            String email,
            @NotBlank @Size(max = 128) String password) {}

    public record EmailRequest(
            @NotBlank @Email @Size(max = 254) @Pattern(regexp = IITK_EMAIL, flags = Pattern.Flag.CASE_INSENSITIVE)
            String email) {}

    public record ResetRequest(
            @NotBlank @Email @Size(max = 254) @Pattern(regexp = IITK_EMAIL, flags = Pattern.Flag.CASE_INSENSITIVE)
            String email,
            @NotBlank @Pattern(regexp = "[0-9]{6}") String code,
            @NotBlank @Size(min = 12, max = 128) String password) {}
}
