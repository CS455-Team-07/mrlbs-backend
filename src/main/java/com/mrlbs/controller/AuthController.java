package com.mrlbs.controller;

import com.mrlbs.security.AdminResourceScope;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

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
