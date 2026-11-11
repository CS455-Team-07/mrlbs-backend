package com.mrlbs.security;

import com.mrlbs.member.Member;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Supplies identity claims to controllers that already accept an OidcUser principal. */
public final class PasswordPrincipal extends DefaultOidcUser {
    private final UUID memberId;
    private final UUID credentialVersion;

    public PasswordPrincipal(Member member) {
        super(List.of(new SimpleGrantedAuthority(AdminResourceScope.isScopedAdmin(member.getEmail())
                        ? "ROLE_ADMIN" : "ROLE_MEMBER")),
                new OidcIdToken("mrlbs-password-session", null, null, Map.of(
                        "sub", member.getId().toString(), "email", member.getEmail(), "name", member.getName())));
        memberId = member.getId();
        credentialVersion = member.getCredentialVersion();
    }

    public UUID memberId() {
        return memberId;
    }

    public UUID credentialVersion() {
        return credentialVersion;
    }
}
