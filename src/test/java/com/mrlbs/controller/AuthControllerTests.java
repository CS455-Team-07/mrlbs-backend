package com.mrlbs.controller;

import com.mrlbs.member.MemberLoginRequest;
import com.mrlbs.member.MemberResponse;
import com.mrlbs.member.MemberService;
import com.mrlbs.security.InstitutionalEmailCodeService;
import com.mrlbs.security.InstitutionalUserPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AuthControllerTests {

    @AfterEach
    void clearSecurityContext() {
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }

    @Test
    void authEnabledMemberLoginRejectsTypedIdentityWithoutOidcPrincipal() {
        MemberService memberService = mock(MemberService.class);
        AuthController controller = new AuthController(memberService, true, mock(InstitutionalEmailCodeService.class));
        MockHttpServletResponse response = new MockHttpServletResponse();

        var result = controller.memberLogin(
                new MemberLoginRequest("ananya@iitk.ac.in", "Ananya"), response, null);

        assertEquals(401, result.getStatusCode().value());
        verifyNoInteractions(memberService);
    }

    @Test
    void authEnabledMemberLoginUsesVerifiedSessionIdentityInsteadOfRequestBody() {
        MemberService memberService = mock(MemberService.class);
        AuthController controller = new AuthController(memberService, true, mock(InstitutionalEmailCodeService.class));
        MockHttpServletResponse response = new MockHttpServletResponse();
        OidcUser user = mock(OidcUser.class);
        when(user.getAttribute("email")).thenReturn("ananya@iitk.ac.in");
        when(user.getAttribute("name")).thenReturn("Ananya");
        MemberResponse member = new MemberResponse(
                UUID.randomUUID(), "ananya@iitk.ac.in", "Ananya", "MEMBER", BigDecimal.valueOf(1000),
                LocalDateTime.now());
        when(memberService.loginOrCreateMember("ananya@iitk.ac.in", "Ananya")).thenReturn(member);

        var result = controller.memberLogin(
                new MemberLoginRequest("victim@iitk.ac.in", "Victim"), response, user);

        assertEquals(200, result.getStatusCode().value());
        assertEquals(0, response.getCookies().length);
        verify(memberService).loginOrCreateMember("ananya@iitk.ac.in", "Ananya");
    }

    @Test
    void verifiedCodeCreatesServerSessionAndReturnsOnlyVerifiedInstitutionalIdentity() {
        MemberService memberService = mock(MemberService.class);
        InstitutionalEmailCodeService codeService = mock(InstitutionalEmailCodeService.class);
        AuthController controller = new AuthController(memberService, true, codeService);
        MockHttpSession session = new MockHttpSession();
        String initialSessionId = session.getId();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setSession(session);
        var principal = new InstitutionalUserPrincipal("mail-ananya@iitk.ac.in", "Ananya", "ananya@iitk.ac.in",
                java.util.List.of(new SimpleGrantedAuthority("ROLE_MEMBER")));
        when(codeService.verifyCode("ananya@iitk.ac.in", "123456", session)).thenReturn(principal);

        var result = controller.verifyCode(new AuthController.VerifyEmailCodeRequest("ananya@iitk.ac.in", "123456"), request);

        assertEquals(200, result.getStatusCode().value());
        assertEquals("ananya@iitk.ac.in", ((AuthController.AuthenticatedUserResponse) result.getBody()).email());
        assertEquals("mail-ananya@iitk.ac.in", ((AuthController.AuthenticatedUserResponse) result.getBody()).id());
        org.junit.jupiter.api.Assertions.assertNotEquals(initialSessionId, request.getSession(false).getId());
        assertEquals(org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication().getName(),
                "mail-ananya@iitk.ac.in");
        verify(codeService).verifyCode("ananya@iitk.ac.in", "123456", session);
        org.junit.jupiter.api.Assertions.assertNotNull(session.getAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY));
    }
}
