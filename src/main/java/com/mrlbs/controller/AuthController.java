package com.mrlbs.controller;
import com.mrlbs.auth.OtpService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.Map;

import com.mrlbs.security.AdminResourceScope;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.mrlbs.member.MemberLoginRequest;
import com.mrlbs.member.MemberResponse;
import com.mrlbs.member.MemberService;
import com.mrlbs.security.DemoAuthenticationFilter;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final MemberService memberService;
    private final OtpService otpService;

    public AuthController(MemberService memberService, OtpService otpService) {
        this.memberService = memberService;
        this.otpService = otpService;
    }

    /**
     * POST /api/auth/login
     * Admins log in directly without OTP.
     * Returning members (found in database) log in directly without OTP.
     * First-time members receive an OTP; once verified, they are stored in the DB for all future logins.
     */
    @PostMapping("/login")
    public ResponseEntity<?> login(
            @Valid @RequestBody LoginRequest body,
            HttpServletResponse response) {
        String email = body.email().trim().toLowerCase();
        String role = body.role() == null ? "member" : body.role().trim().toLowerCase();

        if ("admin".equalsIgnoreCase(role)) {
            if (!AdminResourceScope.isScopedAdmin(email)) {
                throw new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.BAD_REQUEST);
            }

            Cookie adminCookie = new Cookie(DemoAuthenticationFilter.COOKIE_NAME, email);
            adminCookie.setPath("/");
            adminCookie.setMaxAge(86400 * 7);
            adminCookie.setHttpOnly(false);
            response.addCookie(adminCookie);

            Cookie clearMemberCookie = new Cookie(DemoAuthenticationFilter.MEMBER_COOKIE_NAME, "");
            clearMemberCookie.setPath("/");
            clearMemberCookie.setMaxAge(0);
            response.addCookie(clearMemberCookie);

            return ResponseEntity.ok(Map.of(
                    "status", "LOGGED_IN",
                    "id", "admin-" + email,
                    "name", email.split("@")[0],
                    "email", email,
                    "role", "admin"
            ));
        }

        // Member flow: Check database
        boolean alreadyRegistered = memberService.isMemberRegistered(email);

        if (alreadyRegistered) {
            MemberResponse member = memberService.loginOrCreateMember(email, null);

            Cookie memberCookie = new Cookie(DemoAuthenticationFilter.MEMBER_COOKIE_NAME, member.email());
            memberCookie.setPath("/");
            memberCookie.setMaxAge(86400 * 7);
            memberCookie.setHttpOnly(false);
            response.addCookie(memberCookie);

            Cookie clearAdminCookie = new Cookie(DemoAuthenticationFilter.COOKIE_NAME, "");
            clearAdminCookie.setPath("/");
            clearAdminCookie.setMaxAge(0);
            response.addCookie(clearAdminCookie);

            return ResponseEntity.ok(Map.of(
                    "status", "LOGGED_IN",
                    "id", member.id().toString(),
                    "name", member.name(),
                    "email", member.email(),
                    "role", "member"
            ));
        } else {
            // First time member: OTP required
            try {
                otpService.generateAndSend(email);
                return ResponseEntity.ok(Map.of(
                        "status", "OTP_REQUIRED",
                        "message", "First-time sign in: a one-time verification code has been sent to " + email
                ));
            } catch (Exception e) {
                log.warn("Could not send a first-time member verification code", e);
                return ResponseEntity.internalServerError().body(Map.of(
                        "message", "Could not send the verification code. Please try again."
                ));
            }
        }
    }

    /** POST /api/auth/request-code  { "email": "user@iitk.ac.in" } */
    @PostMapping("/request-code")
    public ResponseEntity<?> requestCode(@Valid @RequestBody EmailRequest body) {
        String email = body.email().trim().toLowerCase();
        try {
            otpService.generateAndSend(email);
            return ResponseEntity.ok(
                    Map.of("message", "A verification code has been sent to " + email));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "message", "The request contains invalid data."
            ));
        } catch (Exception e) {
            log.warn("Could not send a verification code", e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "message", "Could not send the verification code. Please try again."
            ));
        }
    }

    /** POST /api/auth/verify-code  { "email": "user@iitk.ac.in", "code": "123456" } */
    @PostMapping("/verify-code")
    public ResponseEntity<?> verifyCode(
            @Valid @RequestBody VerificationRequest body,
            HttpServletResponse response) {
        String email = body.email().trim().toLowerCase();
        String code = body.code().trim();

        if (!otpService.verify(email, code)) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.UNAUTHORIZED);
        }

        // OTP is valid → determine role
        boolean isScopedAdmin = AdminResourceScope.isScopedAdmin(email);

        if (isScopedAdmin) {
            Cookie adminCookie = new Cookie(DemoAuthenticationFilter.COOKIE_NAME, email);
            adminCookie.setPath("/");
            adminCookie.setMaxAge(86400 * 7);
            adminCookie.setHttpOnly(false);
            response.addCookie(adminCookie);

            Cookie clearMemberCookie = new Cookie(DemoAuthenticationFilter.MEMBER_COOKIE_NAME, "");
            clearMemberCookie.setPath("/");
            clearMemberCookie.setMaxAge(0);
            response.addCookie(clearMemberCookie);

            return ResponseEntity.ok(Map.of(
                    "id", "admin-" + email,
                    "name", email.split("@")[0],
                    "email", email,
                    "role", "admin"
            ));
        } else {
            MemberResponse member = memberService.loginOrCreateMember(email, null);

            Cookie memberCookie = new Cookie(
                    DemoAuthenticationFilter.MEMBER_COOKIE_NAME, member.email());
            memberCookie.setPath("/");
            memberCookie.setMaxAge(86400 * 7);
            memberCookie.setHttpOnly(false);
            response.addCookie(memberCookie);

            Cookie clearAdminCookie = new Cookie(DemoAuthenticationFilter.COOKIE_NAME, "");
            clearAdminCookie.setPath("/");
            clearAdminCookie.setMaxAge(0);
            response.addCookie(clearAdminCookie);

            return ResponseEntity.ok(member);
        }
    }

    @PostMapping("/logout")
    public ResponseEntity<?> logout(HttpServletResponse response) {
        Cookie clearMember = new Cookie(DemoAuthenticationFilter.MEMBER_COOKIE_NAME, "");
        clearMember.setPath("/");
        clearMember.setMaxAge(0);
        response.addCookie(clearMember);

        Cookie clearAdmin = new Cookie(DemoAuthenticationFilter.COOKIE_NAME, "");
        clearAdmin.setPath("/");
        clearAdmin.setMaxAge(0);
        response.addCookie(clearAdmin);

        Cookie clearUser = new Cookie("DEMO_USER_EMAIL", "");
        clearUser.setPath("/");
        clearUser.setMaxAge(0);
        response.addCookie(clearUser);

        org.springframework.security.core.context.SecurityContextHolder.clearContext();
        return ResponseEntity.ok(Map.of("message", "Logged out successfully"));
    }

    @PostMapping("/member-login")
    public ResponseEntity<MemberResponse> memberLogin(
            @Valid @RequestBody MemberLoginRequest request,
            HttpServletResponse response) {
        MemberResponse member = memberService.loginOrCreateMember(request.email(), request.name());

        // Only set the member cookie — never write DEMO_ADMIN_EMAIL with a member
        // address, as that would give ROLE_MEMBER to any subsequent admin request
        // that the DemoAuthenticationFilter processes before the admin cookie.
        Cookie memberCookie = new Cookie(DemoAuthenticationFilter.MEMBER_COOKIE_NAME, member.email());
        memberCookie.setPath("/");
        memberCookie.setMaxAge(86400 * 7);
        memberCookie.setHttpOnly(false);
        response.addCookie(memberCookie);

        // Explicitly clear any stale admin-identity cookie so a previous admin
        // session does not bleed into this member session.
        Cookie clearAdminCookie = new Cookie(DemoAuthenticationFilter.COOKIE_NAME, "");
        clearAdminCookie.setPath("/");
        clearAdminCookie.setMaxAge(0);
        response.addCookie(clearAdminCookie);

        return ResponseEntity.ok(member);
    }

    @GetMapping("/me")
    public AuthenticatedUserResponse currentUser(@AuthenticationPrincipal OidcUser user) {
        String email = user.getEmail();
        String scope = AdminResourceScope.scopeForEmail(email).orElse(null);
        return new AuthenticatedUserResponse(
                user.getSubject(),
                user.getFullName(),
                email,
                scope);
    }

    @GetMapping("/csrf")
    public CsrfTokenResponse csrfToken(CsrfToken csrfToken) {
        return new CsrfTokenResponse(csrfToken.getToken(), csrfToken.getHeaderName());
    }

    /** @param resourceTypeScope null means the admin can manage all resource types. */
    public record AuthenticatedUserResponse(String id, String name, String email, String resourceTypeScope) {
    }

    public record CsrfTokenResponse(String token, String headerName) {
    }

    public record LoginRequest(
            @NotBlank @Email @Size(max = 254)
            @Pattern(regexp = "^[A-Za-z0-9._%+-]+@iitk\\.ac\\.in$", flags = Pattern.Flag.CASE_INSENSITIVE,
                    message = "email must use the @iitk.ac.in domain") String email,
            @Pattern(regexp = "(?i)member|admin", message = "role must be member or admin") String role) {
    }

    public record EmailRequest(
            @NotBlank @Email @Size(max = 254)
            @Pattern(regexp = "^[A-Za-z0-9._%+-]+@iitk\\.ac\\.in$", flags = Pattern.Flag.CASE_INSENSITIVE,
                    message = "email must use the @iitk.ac.in domain") String email) {
    }

    public record VerificationRequest(
            @NotBlank @Email @Size(max = 254)
            @Pattern(regexp = "^[A-Za-z0-9._%+-]+@iitk\\.ac\\.in$", flags = Pattern.Flag.CASE_INSENSITIVE,
                    message = "email must use the @iitk.ac.in domain") String email,
            @NotBlank @Pattern(regexp = "\\d{6}", message = "code must contain exactly 6 digits") String code) {
    }
}
