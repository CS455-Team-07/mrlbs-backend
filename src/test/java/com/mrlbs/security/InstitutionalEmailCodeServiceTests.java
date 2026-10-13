package com.mrlbs.security;

import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class InstitutionalEmailCodeServiceTests {

    @Test
    void sendsCodeOnlyToConfiguredInstitutionalDomainAndAuthenticatesAfterVerification() {
        JavaMailSender mailSender = mock(JavaMailSender.class);
        ObjectProvider<JavaMailSender> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(mailSender);
        InstitutionalEmailCodeService service = new InstitutionalEmailCodeService(provider, "iitk.ac.in", "noreply@example.org");
        HttpSession session = mock(HttpSession.class);
        var challenge = new java.util.concurrent.atomic.AtomicReference<Object>();
        when(session.getAttribute(InstitutionalEmailCodeService.CHALLENGE_SESSION_KEY)).thenAnswer(call -> challenge.get());
        doAnswer(call -> { challenge.set(call.getArgument(1)); return null; })
                .when(session).setAttribute(eq(InstitutionalEmailCodeService.CHALLENGE_SESSION_KEY), any());
        doAnswer(call -> { challenge.set(null); return null; })
                .when(session).removeAttribute(InstitutionalEmailCodeService.CHALLENGE_SESSION_KEY);

        service.sendCode("Ananya@iitk.ac.in", session);
        var messageCaptor = org.mockito.ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(messageCaptor.capture());
        assertArrayEquals(new String[]{"ananya@iitk.ac.in"}, messageCaptor.getValue().getTo());
        String code = Pattern.compile("\\b(\\d{6})\\b").matcher(messageCaptor.getValue().getText()).results()
                .map(result -> result.group(1)).findFirst().orElseThrow();

        assertThrows(IllegalArgumentException.class,
                () -> service.verifyCode("someoneelse@iitk.ac.in", code, session));
        InstitutionalUserPrincipal principal = service.verifyCode("ananya@iitk.ac.in", code, session);
        assertEquals("ananya@iitk.ac.in", principal.email());
        assertTrue(principal.getAuthorities().stream().anyMatch(authority -> authority.getAuthority().equals("ROLE_MEMBER")));
        assertNull(challenge.get(), "A successful verification must consume the code.");
        assertThrows(IllegalArgumentException.class, () -> service.verifyCode("ananya@iitk.ac.in", code, session));
    }

    @Test
    void rejectsNonInstitutionalAddressesBeforeSendingMail() {
        JavaMailSender mailSender = mock(JavaMailSender.class);
        ObjectProvider<JavaMailSender> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(mailSender);
        InstitutionalEmailCodeService service = new InstitutionalEmailCodeService(provider, "iitk.ac.in", "noreply@example.org");

        assertThrows(IllegalArgumentException.class,
                () -> service.sendCode("person@example.com", mock(HttpSession.class)));
        verifyNoInteractions(mailSender);
    }

    @Test
    void limitsCodeGuessAttempts() {
        JavaMailSender mailSender = mock(JavaMailSender.class);
        ObjectProvider<JavaMailSender> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(mailSender);
        InstitutionalEmailCodeService service = new InstitutionalEmailCodeService(provider, "iitk.ac.in", "noreply@example.org");
        HttpSession session = mock(HttpSession.class);
        var challenge = new java.util.concurrent.atomic.AtomicReference<Object>();
        when(session.getAttribute(InstitutionalEmailCodeService.CHALLENGE_SESSION_KEY)).thenAnswer(call -> challenge.get());
        doAnswer(call -> { challenge.set(call.getArgument(1)); return null; })
                .when(session).setAttribute(eq(InstitutionalEmailCodeService.CHALLENGE_SESSION_KEY), any());
        doAnswer(call -> { challenge.set(null); return null; })
                .when(session).removeAttribute(InstitutionalEmailCodeService.CHALLENGE_SESSION_KEY);

        service.sendCode("member@iitk.ac.in", session);
        var messageCaptor = org.mockito.ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(messageCaptor.capture());
        String sentCode = Pattern.compile("\\b(\\d{6})\\b").matcher(messageCaptor.getValue().getText()).results()
                .map(result -> result.group(1)).findFirst().orElseThrow();
        String incorrectCode = sentCode.equals("000000") ? "000001" : "000000";
        for (int i = 0; i < 5; i++) {
            assertThrows(IllegalArgumentException.class,
                    () -> service.verifyCode("member@iitk.ac.in", incorrectCode, session));
        }
        assertNull(challenge.get());
    }
}
