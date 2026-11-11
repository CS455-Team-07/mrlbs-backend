package com.mrlbs.security;

import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Service;

@Service
public class InstitutionalOidcUserService extends OidcUserService {

    private final InstitutionalIdentityValidator identityValidator;

    public InstitutionalOidcUserService(InstitutionalIdentityValidator identityValidator) {
        this.identityValidator = identityValidator;
    }

    @Override
    public OidcUser loadUser(OidcUserRequest userRequest) throws OAuth2AuthenticationException {
        OidcUser user = super.loadUser(userRequest);
        identityValidator.validate(user);
        var authorities = new java.util.ArrayList<GrantedAuthority>(user.getAuthorities());
        authorities.add(new SimpleGrantedAuthority(AdminResourceScope.isScopedAdmin(user.getEmail())
                ? "ROLE_ADMIN" : "ROLE_MEMBER"));
        return new DefaultOidcUser(authorities, user.getIdToken(), user.getUserInfo());
    }
}
