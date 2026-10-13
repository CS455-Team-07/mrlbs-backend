package com.mrlbs.controller;

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
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final MemberService memberService;

    public AuthController(MemberService memberService) {
        this.memberService = memberService;
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
}
