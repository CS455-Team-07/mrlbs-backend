package com.mrlbs.security;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Legacy OIDC helper retained for tests and compatibility; it is not registered as a Spring bean. */
public class InstitutionalOidcUserService extends OidcUserService {
    private final InstitutionalIdentityValidator identityValidator;

    public InstitutionalOidcUserService(InstitutionalIdentityValidator identityValidator) {
        this.identityValidator = identityValidator;
    }

    @Override
    public OidcUser loadUser(OidcUserRequest userRequest) throws OAuth2AuthenticationException {
        OidcUser user = super.loadUser(userRequest);
        identityValidator.validate(user);
        Map<String, Object> claims = new HashMap<>(user.getIdToken().getClaims());
        claims.put("email", InstitutionalIdentityValidator.institutionalEmail(user));
        var token = new org.springframework.security.oauth2.core.oidc.OidcIdToken(
                user.getIdToken().getTokenValue(), user.getIdToken().getIssuedAt(), user.getIdToken().getExpiresAt(), claims);
        return new DefaultOidcUser(applicationAuthorities(user), token, user.getUserInfo(), "sub");
    }

    static List<GrantedAuthority> applicationAuthorities(OidcUser user) {
        List<GrantedAuthority> authorities = new ArrayList<>(user.getAuthorities());
        String email = InstitutionalIdentityValidator.institutionalEmail(user);
        authorities.add(new SimpleGrantedAuthority(
                AdminResourceScope.isScopedAdmin(email) ? "ROLE_ADMIN" : "ROLE_MEMBER"));
        return authorities;
    }
}
