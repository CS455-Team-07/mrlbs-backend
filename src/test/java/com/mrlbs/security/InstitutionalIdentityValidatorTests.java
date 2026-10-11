package com.mrlbs.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class InstitutionalIdentityValidatorTests {

    private final InstitutionalIdentityValidator validator =
            new InstitutionalIdentityValidator(" IITK.AC.IN ");

    @Test
    void acceptsVerifiedGoogleWorkspaceIdentityFromConfiguredDomain() {
        assertDoesNotThrow(() -> validator.validate(user("https://accounts.google.com", "iitk.ac.in", true)));
    }

    @Test
    void rejectsIdentityFromAnotherGoogleWorkspaceDomain() {
        assertDomainRejected(user("https://accounts.google.com", "gmail.com", true));
    }

    @Test
    void rejectsIdentityWithoutHostedDomainEvenWhenEmailUsesInstitutionalSuffix() {
        assertDomainRejected(user("https://accounts.google.com", null, true));
    }

    @Test
    void rejectsUnverifiedEmail() {
        assertDomainRejected(user("https://accounts.google.com", "iitk.ac.in", false));
    }

    @Test
    void rejectsIdentityFromUntrustedIssuer() {
        assertDomainRejected(user("https://accounts.example.com", "iitk.ac.in", true));
    }

    private void assertDomainRejected(OidcUser user) {
        OAuth2AuthenticationException exception = assertThrows(
                OAuth2AuthenticationException.class,
                () -> validator.validate(user));
        assertEquals("institutional_domain_required", exception.getError().getErrorCode());
    }

    private OidcUser user(String issuer, String hostedDomain, boolean emailVerified) {
        Map<String, Object> claims = new java.util.HashMap<>();
        claims.put("iss", issuer);
        claims.put("email_verified", emailVerified);
        if (hostedDomain != null) {
            claims.put("hd", hostedDomain);
        }

        Instant now = Instant.now();
        OidcIdToken idToken = new OidcIdToken("test-token", now, now.plusSeconds(300), claims);
        OidcUser user = mock(OidcUser.class);
        when(user.getIdToken()).thenReturn(idToken);
        return user;
    }
}
