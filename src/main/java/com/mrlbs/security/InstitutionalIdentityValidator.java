package com.mrlbs.security;

import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

import java.util.Locale;

/** Legacy OIDC identity validator retained for compatibility; mailbox OTP is the active login flow. */
public class InstitutionalIdentityValidator {
    private final String institutionalDomain;
    private final String tenantId;

    public InstitutionalIdentityValidator(String institutionalDomain, String tenantId) {
        this.institutionalDomain = institutionalDomain.trim().toLowerCase(Locale.ROOT);
        this.tenantId = tenantId.trim().toLowerCase(Locale.ROOT);
    }

    public void validate(OidcUser user) throws OAuth2AuthenticationException {
        String email = institutionalEmail(user);
        String issuer = user.getIdToken().getIssuer() == null ? "" : user.getIdToken().getIssuer().toString();
        String tokenTenant = user.getIdToken().getClaimAsString("tid");
        boolean validIssuer = issuer.equalsIgnoreCase("https://login.microsoftonline.com/" + tenantId + "/v2.0");
        boolean validTenant = tenantId.equalsIgnoreCase(tokenTenant == null ? "" : tokenTenant);
        boolean validDomain = email != null && email.trim().toLowerCase(Locale.ROOT)
                .endsWith("@" + institutionalDomain);
        if (!validIssuer || !validTenant || !validDomain) {
            throw new OAuth2AuthenticationException(new OAuth2Error(
                    "institutional_domain_required", "Use an IITK account from the configured tenant.", null));
        }
    }

    static String institutionalEmail(OidcUser user) {
        String email = user.getIdToken().getClaimAsString("email");
        if (email == null || email.isBlank()) email = user.getIdToken().getClaimAsString("preferred_username");
        if (email == null || email.isBlank()) email = user.getIdToken().getClaimAsString("upn");
        return email;
    }
}
