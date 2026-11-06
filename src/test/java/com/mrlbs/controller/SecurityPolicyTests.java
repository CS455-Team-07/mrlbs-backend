package com.mrlbs.controller;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "mrlbs.security.auth-per-minute=2",
        "mrlbs.security.booking-per-minute=2",
        "management.health.mail.enabled=false"
})
@ActiveProfiles("test")
@AutoConfigureMockMvc
class SecurityPolicyTests {
    @Autowired MockMvc mvc;
    @MockitoBean JavaMailSender sender;

    @Test
    void onlyMailboxVerificationCreatesSessionAndLogoutRevokesIt() throws Exception {
        String email = "security-policy-test@iitk.ac.in";
        AtomicReference<String> code = new AtomicReference<>();
        doAnswer(invocation -> {
            SimpleMailMessage mail = invocation.getArgument(0);
            assertThat(mail.getTo()).containsExactly(email);
            var match = Pattern.compile("[0-9]{6}").matcher(mail.getText());
            assertThat(match.find()).isTrue();
            code.set(match.group());
            return null;
        }).when(sender).send(any(SimpleMailMessage.class));

        mvc.perform(get("/api/bookings").header("X-User-Email", email)
                        .cookie(new Cookie("DEMO_MEMBER_EMAIL", email)))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/login").with(source("192.0.2.10"))
                        .contentType(APPLICATION_JSON).content("{\"email\":\"" + email + "\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("OTP_REQUIRED"));
        var verification = mvc.perform(post("/api/auth/verify-code").with(source("192.0.2.10"))
                        .contentType(APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"code\":\"" + code.get() + "\"}"))
                .andExpect(status().isOk()).andReturn();
        var session = (MockHttpSession) verification.getRequest().getSession(false);
        assertThat(session).isNotNull();

        mvc.perform(get("/api/auth/me").session(session).header("X-User-Email", "lhc@iitk.ac.in"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.email").value(email));
        mvc.perform(get("/api/bookings/approvals").session(session))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/auth/logout").session(session).with(csrf()))
                .andExpect(status().isOk());
        assertThat(session.isInvalid()).isTrue();
    }

    @Test
    void forwardedHeadersCannotBypassAuthLimitsAndBookingsShareAccountQuota() throws Exception {
        for (int attempt = 1; attempt <= 3; attempt++) {
            mvc.perform(post("/api/auth/login").with(source("192.0.2.20"))
                            .header("X-Forwarded-For", "198.51.100." + attempt)
                            .contentType(APPLICATION_JSON).content("{}"))
                    .andExpect(status().is(attempt < 3 ? 400 : 429))
                    .andExpect(header().exists("X-Request-ID"));
        }

        for (int attempt = 1; attempt <= 3; attempt++) {
            var request = mvc.perform(post(attempt == 1 ? "/api/bookings" : "/api/bookings/draft")
                            .with(source("192.0.2." + (30 + attempt))).with(csrf())
                            .with(oidcLogin().idToken(token -> token.claim("email", "booking-limit@iitk.ac.in"))
                                    .authorities(new SimpleGrantedAuthority("ROLE_MEMBER")))
                            .contentType(APPLICATION_JSON).content("{}"))
                    .andExpect(status().is(attempt < 3 ? 400 : 429));
            if (attempt == 3) {
                request.andExpect(header().string("Retry-After", "60"));
            }
        }
    }

    private static RequestPostProcessor source(String address) {
        return request -> {
            request.setRemoteAddr(address);
            return request;
        };
    }
}
