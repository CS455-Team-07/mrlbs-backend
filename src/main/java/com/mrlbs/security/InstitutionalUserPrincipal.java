package com.mrlbs.security;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.core.user.OAuth2User;

import java.util.Collection;
import java.util.Map;

/** Session principal created only after a one-time code is verified. */
public record InstitutionalUserPrincipal(
        String id,
        String name,
        String email,
        Collection<? extends GrantedAuthority> authorities) implements OAuth2User {

    @Override
    public Map<String, Object> getAttributes() {
        return Map.of("sub", id, "name", name, "email", email, "email_verified", true);
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return authorities;
    }

    @Override
    public String getName() {
        return id;
    }
}
