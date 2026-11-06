package com.mrlbs.controller;

import com.mrlbs.auth.OtpService;
import com.mrlbs.member.MemberLoginRequest;
import com.mrlbs.member.MemberResponse;
import com.mrlbs.member.MemberService;
import com.mrlbs.security.AdminResourceScope;
import com.mrlbs.security.DemoAuthenticationFilter;
import com.mrlbs.security.RequestRateLimiter;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final MemberService memberService;
    private final OtpService otpService;
    private final RequestRateLimiter limiter;
    private final boolean demo;
    private final String institutionalDomain;
    private final int sendsPerMinute;
    private final int checksPerMinute;
    private final HttpSessionSecurityContextRepository sessions = new HttpSessionSecurityContextRepository();

    public AuthController(MemberService memberService, OtpService otpService, RequestRateLimiter limiter,
                          Environment environment,
                          @Value("${mrlbs.auth.institutional-domain}") String institutionalDomain,
                          @Value("${mrlbs.security.otp-sends-per-minute:3}") int sendsPerMinute,
                          @Value("${mrlbs.security.otp-checks-per-minute:5}") int checksPerMinute) {
        this.memberService = memberService;
        this.otpService = otpService;
        this.limiter = limiter;
        this.demo = DemoAuthenticationFilter.isEnabled(environment);
        this.institutionalDomain = normalize(institutionalDomain);
        this.sendsPerMinute = sendsPerMinute;
        this.checksPerMinute = checksPerMinute;
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody SignIn body, HttpServletResponse response) {
        String email = normalize(body.email());
        if (!isInstitutionalAddress(email)) {
            return ResponseEntity.badRequest().body(Map.of("message", "Only @" + institutionalDomain + " email addresses are accepted."));
        }
        boolean admin = AdminResourceScope.isScopedAdmin(email);
        if ("admin".equalsIgnoreCase(body.role()) && !admin) {
            return ResponseEntity.badRequest().body(Map.of("message", "This email is not an authorized administrator."));
        }
        if (demo && (admin || memberService.isMemberRegistered(email))) {
            Identity identity = identity(email);
            setDemoIdentity(response, identity);
            return ResponseEntity.ok(Map.of("status", "LOGGED_IN", "id", identity.id(), "name", identity.name(),
                    "email", email, "role", identity.role()));
        }
        return sendCode(email);
    }

    @PostMapping("/request-code")
    public ResponseEntity<?> requestCode(@RequestBody SignIn body) {
        return sendCode(normalize(body.email()));
    }

    private ResponseEntity<?> sendCode(String email) {
        if (!isInstitutionalAddress(email)) {
            return ResponseEntity.badRequest().body(Map.of("message", "Only @" + institutionalDomain + " email addresses are accepted."));
        }
        if (!limiter.allow("otp-send:" + email, sendsPerMinute)) {
            return rateLimited();
        }
        try {
            otpService.generateAndSend(email);
            return ResponseEntity.ok(Map.of("status", "OTP_REQUIRED", "message", "A verification code has been sent."));
        } catch (org.springframework.mail.MailException exception) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("message", "Could not send the verification code. Please try again."));
        }
    }

    @PostMapping("/verify-code")
    public ResponseEntity<?> verifyCode(@RequestBody SignIn body, HttpServletRequest request,
                                        HttpServletResponse response) {
        String email = normalize(body.email());
        if (!isInstitutionalAddress(email) || body.code() == null || !body.code().strip().matches("[0-9]{6}")) {
            return ResponseEntity.badRequest().body(Map.of("message", "A valid email and six-digit code are required."));
        }
        if (!limiter.allow("otp-check:" + email, checksPerMinute)) {
            return rateLimited();
        }
        if (!otpService.verify(email, body.code())) {
            return ResponseEntity.status(401).body(Map.of("message", "Invalid or expired verification code."));
        }
        Identity identity = identity(email);
        if (demo) {
            setDemoIdentity(response, identity);
        } else {
            createVerifiedSession(request, response, identity);
        }
        return ResponseEntity.ok(identity);
    }

    @PostMapping("/logout")
    public ResponseEntity<?> logout(HttpServletRequest request, HttpServletResponse response) {
        new SecurityContextLogoutHandler().logout(request, response, SecurityContextHolder.getContext().getAuthentication());
        if (demo) {
            addDemoCookie(response, DemoAuthenticationFilter.MEMBER_COOKIE_NAME, "", 0);
            addDemoCookie(response, DemoAuthenticationFilter.COOKIE_NAME, "", 0);
            addDemoCookie(response, "DEMO_USER_EMAIL", "", 0);
        }
        return ResponseEntity.ok(Map.of("message", "Logged out successfully"));
    }

    @PostMapping("/member-login")
    public ResponseEntity<MemberResponse> memberLogin(@Valid @RequestBody MemberLoginRequest request,
                                                       @AuthenticationPrincipal OidcUser user,
                                                       HttpServletResponse response) {
        if (!demo && (user == null || AdminResourceScope.isScopedAdmin(user.getEmail())
                || !normalize(request.email()).equals(normalize(user.getEmail())))) {
            return ResponseEntity.status(403).build();
        }
        MemberResponse member = demo ? memberService.loginOrCreateMember(request.email(), request.name())
                : memberService.loginOrCreateMember(user.getEmail(), user.getFullName());
        if (demo) {
            setDemoIdentity(response, new Identity(member.id().toString(), member.name(), member.email(), "member", null));
        }
        return ResponseEntity.ok(member);
    }

    @GetMapping("/me")
    public Identity currentUser(@AuthenticationPrincipal OidcUser user) {
        return new Identity(user.getSubject(), user.getFullName(), user.getEmail(),
                AdminResourceScope.isScopedAdmin(user.getEmail()) ? "admin" : "member",
                AdminResourceScope.scopeForEmail(user.getEmail()).orElse(null));
    }

    @GetMapping("/csrf")
    public CsrfTokenResponse csrfToken(CsrfToken token) {
        return new CsrfTokenResponse(token.getToken(), token.getHeaderName());
    }

    private Identity identity(String email) {
        String scope = AdminResourceScope.scopeForEmail(email).orElse(null);
        if (scope != null) {
            return new Identity("admin-" + scope, email.substring(0, email.indexOf('@')), email, "admin", scope);
        }
        MemberResponse member = memberService.loginOrCreateMember(email, null);
        return new Identity(member.id().toString(), member.name(), email, "member", null);
    }

    private void createVerifiedSession(HttpServletRequest request, HttpServletResponse response, Identity identity) {
        if (request.getSession(false) != null) {
            request.changeSessionId();
        }
        Instant now = Instant.now();
        var claims = Map.<String, Object>of("sub", identity.id(), "email", identity.email(),
                "email_verified", true, "name", identity.name());
        var token = new OidcIdToken("mailbox-verified-session", now, now.plus(30, ChronoUnit.MINUTES), claims);
        var roles = List.of(new SimpleGrantedAuthority("ROLE_" + identity.role().toUpperCase(Locale.ROOT)));
        var user = new DefaultOidcUser(roles, token);
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(user, null, roles));
        SecurityContextHolder.setContext(context);
        sessions.saveContext(context, request, response);
    }

    private boolean isInstitutionalAddress(String email) {
        int separator = email.indexOf('@');
        return separator > 0 && separator <= 64
                && email.substring(separator + 1).equals(institutionalDomain)
                && email.substring(0, separator).matches("[a-z0-9_+.-]+");
    }

    private static String normalize(String value) {
        return value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
    }

    private static ResponseEntity<?> rateLimited() {
        return ResponseEntity.status(429).header("Retry-After", "60")
                .body(Map.of("message", "Too many requests. Please try again in a minute."));
    }

    private static void setDemoIdentity(HttpServletResponse response, Identity identity) {
        boolean admin = "admin".equals(identity.role());
        addDemoCookie(response, DemoAuthenticationFilter.COOKIE_NAME, admin ? identity.email() : "", admin ? 604800 : 0);
        addDemoCookie(response, DemoAuthenticationFilter.MEMBER_COOKIE_NAME, admin ? "" : identity.email(), admin ? 0 : 604800);
    }

    private static void addDemoCookie(HttpServletResponse response, String name, String email, int maxAge) {
        Cookie cookie = new Cookie(name, email);
        cookie.setPath("/");
        cookie.setMaxAge(maxAge);
        cookie.setHttpOnly(true);
        response.addCookie(cookie);
    }

    public record SignIn(String email, String role, String code) {}
    public record Identity(String id, String name, String email, String role, String resourceTypeScope) {}
    public record CsrfTokenResponse(String token, String headerName) {}
}
