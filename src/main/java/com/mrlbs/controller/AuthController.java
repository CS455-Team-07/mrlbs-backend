package com.mrlbs.controller;

import com.mrlbs.security.AdminResourceScope;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.mrlbs.member.MemberLoginRequest;
import com.mrlbs.member.MemberResponse;
import com.mrlbs.member.MemberService;
import com.mrlbs.security.DemoAuthenticationFilter;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import com.mrlbs.security.InstitutionalEmailCodeService;
import com.mrlbs.security.InstitutionalUserPrincipal;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final MemberService memberService;
    private final boolean authenticationEnabled;
    private final InstitutionalEmailCodeService emailCodeService;

    public AuthController(
            MemberService memberService,
            @Value("${mrlbs.auth.enabled:false}") boolean authenticationEnabled,
            InstitutionalEmailCodeService emailCodeService) {
        this.memberService = memberService;
        this.authenticationEnabled = authenticationEnabled;
        this.emailCodeService = emailCodeService;
    }

    @PostMapping("/member-login")
    public ResponseEntity<MemberResponse> memberLogin(
            @Valid @RequestBody MemberLoginRequest request,
            HttpServletResponse response,
            @AuthenticationPrincipal OAuth2User authenticatedUser) {
        String email;
        String name;
        if (authenticationEnabled) {
            if (authenticatedUser == null) {
                return ResponseEntity.status(org.springframework.http.HttpStatus.UNAUTHORIZED).build();
            }
            email = authenticatedUser.getAttribute("email");
            name = authenticatedUser.getAttribute("name");
        } else {
            email = request.email();
            name = request.name();
        }

        MemberResponse member = memberService.loginOrCreateMember(email, name);

        if (!authenticationEnabled) {
            Cookie memberCookie = new Cookie(DemoAuthenticationFilter.MEMBER_COOKIE_NAME, member.email());
            memberCookie.setPath("/");
            memberCookie.setMaxAge(86400 * 7);
            memberCookie.setHttpOnly(false);
            response.addCookie(memberCookie);

            // Clear any stale admin identity so it cannot bleed into this member session.
            Cookie clearAdminCookie = new Cookie(DemoAuthenticationFilter.COOKIE_NAME, "");
            clearAdminCookie.setPath("/");
            clearAdminCookie.setMaxAge(0);
            response.addCookie(clearAdminCookie);
        }

        return ResponseEntity.ok(member);
    }

    @PostMapping("/request-code")
    public ResponseEntity<?> requestCode(
            @Valid @RequestBody EmailCodeRequest request,
            HttpServletRequest servletRequest) {
        try {
            emailCodeService.sendCode(request.email(), servletRequest.getSession(true));
            return ResponseEntity.accepted().body(new MessageResponse("A verification code was sent to your institutional email."));
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest().body(new MessageResponse(exception.getMessage()));
        } catch (IllegalStateException exception) {
            int status = exception.getMessage().startsWith("A code was already")
                    ? 429 : 503;
            return ResponseEntity.status(status).body(new MessageResponse(exception.getMessage()));
        }
    }

    @PostMapping("/verify-code")
    public ResponseEntity<?> verifyCode(
            @Valid @RequestBody VerifyEmailCodeRequest request,
            HttpServletRequest servletRequest) {
        try {
            HttpSession session = servletRequest.getSession(false);
            if (session == null) {
                return ResponseEntity.badRequest().body(new MessageResponse("Request a new verification code first."));
            }
            InstitutionalUserPrincipal principal = emailCodeService.verifyCode(request.email(), request.code(), session);
            Authentication authentication = new OAuth2AuthenticationToken(
                    principal, principal.authorities(), "institutional-email");
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(authentication);
            SecurityContextHolder.setContext(context);
            servletRequest.changeSessionId();
            servletRequest.getSession().setAttribute(
                    HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
            return ResponseEntity.ok(new AuthenticatedUserResponse(
                    principal.id(), principal.name(), principal.email(),
                    AdminResourceScope.scopeForEmail(principal.email()).orElse(null)));
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest().body(new MessageResponse(exception.getMessage()));
        }
    }

    @GetMapping("/me")
    public AuthenticatedUserResponse currentUser(@AuthenticationPrincipal OAuth2User user) {
        String email = user.getAttribute("email");
        String scope = AdminResourceScope.scopeForEmail(email).orElse(null);
        return new AuthenticatedUserResponse(
                user.getName(),
                user.getAttribute("name"),
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

    public record EmailCodeRequest(@jakarta.validation.constraints.NotBlank String email) { }

    public record VerifyEmailCodeRequest(
            @jakarta.validation.constraints.NotBlank String email,
            @jakarta.validation.constraints.Pattern(regexp = "\\d{6}") String code) { }

    public record MessageResponse(String message) { }
}
