package com.mrlbs.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Supplies a local demo identity when authentication is disabled.
 *  The active admin email is read from the {@code DEMO_ADMIN_EMAIL} cookie so
 *  the frontend demo-switcher can change identities without a backend restart.
 */
public class DemoAuthenticationFilter extends OncePerRequestFilter {

    /** Cookie name written by DemoAdminController. */
    /** Cookie name written by DemoAdminController. */
    public static final String COOKIE_NAME = "DEMO_ADMIN_EMAIL";
    public static final String MEMBER_COOKIE_NAME = "DEMO_MEMBER_EMAIL";

    private static final List<SimpleGrantedAuthority> ADMIN_AUTHORITIES =
            List.of(new SimpleGrantedAuthority("ROLE_ADMIN"));
    private static final List<SimpleGrantedAuthority> MEMBER_AUTHORITIES =
            List.of(new SimpleGrantedAuthority("ROLE_MEMBER"));

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {

        String email = resolveEmail(request);

        // No demo cookie present → let the request pass through unauthenticated
        if (email.isEmpty()) {
            filterChain.doFilter(request, response);
            return;
        }

        boolean isScopedAdmin = AdminResourceScope.isScopedAdmin(email);
        List<SimpleGrantedAuthority> authorities = isScopedAdmin ? ADMIN_AUTHORITIES : MEMBER_AUTHORITIES;
        String name = resolveName(email, isScopedAdmin);

        Instant now = Instant.now();
        OidcIdToken idToken = new OidcIdToken(
                "local-demo-token",
                now,
                now.plusSeconds(3600),
                Map.of(
                        "iss",            "https://accounts.google.com",
                        "sub",            "demo-" + email,
                        "email",          email,
                        "email_verified", true,
                        "name",           name,
                        "hd",             email.contains("@") ? email.split("@")[1] : "iitk.ac.in"));

        DefaultOidcUser principal = new DefaultOidcUser(authorities, idToken, "sub");
        OAuth2AuthenticationToken authentication =
                new OAuth2AuthenticationToken(principal, authorities, "google");

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        filterChain.doFilter(request, response);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static String resolveEmail(HttpServletRequest request) {
        if (request.getCookies() == null) return "";
        // Strictly prefer the admin-identity cookie so that a DEMO_MEMBER_EMAIL
        // cookie from a previous session cannot shadow a freshly issued admin identity.
        java.util.Optional<String> adminEmail = Arrays.stream(request.getCookies())
                .filter(c -> COOKIE_NAME.equals(c.getName()))
                .map(Cookie::getValue)
                .filter(v -> v != null && !v.isBlank())
                .findFirst();
        if (adminEmail.isPresent()) return adminEmail.get();

        return Arrays.stream(request.getCookies())
                .filter(c -> MEMBER_COOKIE_NAME.equals(c.getName())
                          || "DEMO_USER_EMAIL".equals(c.getName()))
                .map(Cookie::getValue)
                .filter(v -> v != null && !v.isBlank())
                .findFirst()
                .orElse("");
    }

    private static String resolveName(String email, boolean isScopedAdmin) {
        if (!isScopedAdmin) {
            return email.isEmpty() ? "Guest" : email.split("@")[0];
        }
        return switch (email.toLowerCase()) {
            case "lhc@iitk.ac.in"   -> "LHC Admin";
            case "gpus@iitk.ac.in"  -> "GPU Admin";
            case "kdlab@iitk.ac.in" -> "KD Lab Admin";
            default                  -> email.split("@")[0];
        };
    }
}
