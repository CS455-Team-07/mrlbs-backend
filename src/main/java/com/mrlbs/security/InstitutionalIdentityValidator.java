package com.mrlbs.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Component;

import java.util.Locale;

@Component
public class InstitutionalIdentityValidator {

    private final String institutionalDomain;
    private final boolean allowDevelopmentDomainBypass;

    public InstitutionalIdentityValidator(String institutionalDomain) {
        this(institutionalDomain, false, new StandardEnvironment());
    }

    @Autowired
    public InstitutionalIdentityValidator(
            @Value("${mrlbs.auth.institutional-domain}") String institutionalDomain,
            @Value("${mrlbs.auth.dev-allow-any-google-domain:false}") boolean devAllowAnyGoogleDomain,
            Environment environment) {
        this.institutionalDomain = institutionalDomain.trim().toLowerCase(Locale.ROOT);
        if (this.institutionalDomain.isBlank()) {
            throw new IllegalArgumentException("The institutional domain must be configured.");
        }
        this.allowDevelopmentDomainBypass = devAllowAnyGoogleDomain
                && environment.acceptsProfiles(Profiles.of("dev"));
    }

    public void validate(OidcUser user) throws OAuth2AuthenticationException {
        String issuer = user.getIdToken().getClaimAsString("iss");
        String hostedDomain = user.getIdToken().getClaimAsString("hd");
        Boolean verifiedEmail = user.getIdToken().getClaim("email_verified");
        boolean trustedGoogleIssuer = "https://accounts.google.com".equals(issuer)
                || "accounts.google.com".equals(issuer);

        boolean institutionalDomainMatches = hostedDomain != null
                && hostedDomain.equalsIgnoreCase(institutionalDomain);

        if (!trustedGoogleIssuer
                || !Boolean.TRUE.equals(verifiedEmail)
                || (!allowDevelopmentDomainBypass && !institutionalDomainMatches)) {
            throw new OAuth2AuthenticationException(new OAuth2Error(
                    "institutional_domain_required",
                    "A verified Google Workspace identity from the configured institutional domain is required.",
                    null));
        }
    }
}
