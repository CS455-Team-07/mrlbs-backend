package com.mrlbs.controller;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.concurrent.atomic.AtomicReference;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "MRLBS_GPUS_INITIAL_PASSWORD=test admin password")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PasswordAuthenticationTests {
    @Autowired MockMvc mvc;
    @MockitoBean JavaMailSender mailSender;

    @Test
    void rejectsClientSuppliedIdentityAndRequiresCsrfForLogin() throws Exception {
        mvc.perform(get("/api/bookings/approvals")
                        .header("X-User-Email", "gpus@iitk.ac.in")
                        .cookie(new Cookie("DEMO_ADMIN_EMAIL", "gpus@iitk.ac.in")))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/login").contentType("application/json")
                        .content("{\"email\":\"gpus@iitk.ac.in\",\"password\":\"anything\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void setsAndResetsMemberPasswordThroughEmailAndRevokesSessions() throws Exception {
        String email = "password-test@iitk.ac.in";
        var deliveredCode = new AtomicReference<String>();
        doAnswer(invocation -> {
            SimpleMailMessage mail = invocation.getArgument(0);
            deliveredCode.set(mail.getText().lines().findFirst().orElseThrow().replaceAll("[^0-9]", ""));
            return null;
        }).when(mailSender).send(any(SimpleMailMessage.class));

        String initial = "first example password";
        String replacement = "second example password";
        mvc.perform(login(email, initial)).andExpect(status().isUnauthorized());
        requestCode(email);
        mvc.perform(reset(email, deliveredCode.get(), initial)).andExpect(status().isNoContent());
        mvc.perform(reset(email, deliveredCode.get(), replacement)).andExpect(status().isBadRequest());

        var session = (MockHttpSession) mvc.perform(login(email, initial))
                .andExpect(status().isOk()).andExpect(jsonPath("$.role").value("member"))
                .andReturn().getRequest().getSession(false);
        mvc.perform(get("/api/auth/me").session(session))
                .andExpect(status().isOk()).andExpect(jsonPath("$.email").value(email));
        mvc.perform(get("/api/auth/member-credits").session(session))
                .andExpect(status().isOk());

        requestCode(email);
        mvc.perform(reset(email, deliveredCode.get(), replacement)).andExpect(status().isNoContent());
        mvc.perform(get("/api/auth/me").session(session)).andExpect(status().isUnauthorized());
        mvc.perform(login(email, initial)).andExpect(status().isUnauthorized());
        var replacementSession = (MockHttpSession) mvc.perform(login(email, replacement))
                .andExpect(status().isOk()).andReturn().getRequest().getSession(false);
        mvc.perform(post("/api/auth/logout").session(replacementSession).with(csrf()))
                .andExpect(status().isNoContent());
        org.assertj.core.api.Assertions.assertThat(replacementSession.isInvalid()).isTrue();
    }

    @Test
    void provisionedAdminSignsInWithoutEmailAndCannotUsePublicRecovery() throws Exception {
        String email = "gpus@iitk.ac.in";
        mvc.perform(login(email, "incorrect password")).andExpect(status().isUnauthorized());
        mvc.perform(login(email, "test admin password"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("admin"))
                .andExpect(jsonPath("$.resourceTypeScope").value("GPU"));
        mvc.perform(authPost("forgot-password", "{\"email\":\"%s\"}".formatted(email)))
                .andExpect(status().isBadRequest());
        mvc.perform(reset(email, "123456", "replacement password")).andExpect(status().isBadRequest());
        org.mockito.Mockito.verifyNoInteractions(mailSender);
    }

    private void requestCode(String email) throws Exception {
        mvc.perform(authPost("forgot-password", "{\"email\":\"%s\"}".formatted(email)))
                .andExpect(status().isNoContent());
    }

    private MockHttpServletRequestBuilder reset(String email, String code, String password) {
        return authPost("reset-password", """
                {"email":"%s","code":"%s","password":"%s"}
                """.formatted(email, code, password));
    }

    private MockHttpServletRequestBuilder login(String email, String password) {
        return authPost("login", """
                {"email":"%s","password":"%s"}
                """.formatted(email, password));
    }

    private MockHttpServletRequestBuilder authPost(String endpoint, String body) {
        return post("/api/auth/" + endpoint).with(csrf()).contentType("application/json").content(body);
    }
}
