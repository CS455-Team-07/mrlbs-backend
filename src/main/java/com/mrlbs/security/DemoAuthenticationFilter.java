package com.mrlbs.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
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
import java.util.List;
import java.util.Map;

/** Supplies a local demo identity when authentication is disabled. */
public class DemoAuthenticationFilter extends OncePerRequestFilter {

    private static final String DEMO_EMAIL = "demo@mrlbs.local";
    private static final List<SimpleGrantedAuthority> AUTHORITIES =
            List.of(new SimpleGrantedAuthority("ROLE_ADMIN"));

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        Instant now = Instant.now();
        OidcIdToken idToken = new OidcIdToken(
                "local-demo-token",
                now,
                now.plusSeconds(3600),
                Map.of(
                        "iss", "https://accounts.google.com",
                        "sub", "local-demo-user",
                        "email", DEMO_EMAIL,
                        "email_verified", true,
                        "name", "Demo Admin",
                        "hd", "mrlbs.local"));
        DefaultOidcUser principal = new DefaultOidcUser(AUTHORITIES, idToken, "sub");
        OAuth2AuthenticationToken authentication = new OAuth2AuthenticationToken(
                principal, AUTHORITIES, "google");

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        filterChain.doFilter(request, response);
    }
}
