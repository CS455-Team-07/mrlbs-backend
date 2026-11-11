package com.mrlbs.ai;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AiControllerTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AiRateLimiter rateLimiter;

    @BeforeEach
    void setUp() {
        rateLimiter.reset();
    }

    @Test
    void unauthenticatedRequest_isRejected() throws Exception {
        mockMvc.perform(get("/api/ai/status"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void getStatus_returnsAiServiceStatus_whenAuthenticated() throws Exception {
        mockMvc.perform(get("/api/ai/status")
                        .with(user("member@iitk.ac.in").roles("MEMBER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.model").exists());
    }

    @Test
    void parseIntent_returnsExtractedIntent() throws Exception {
        String payload = """
                {
                    "prompt": "I need a lecture hall for 80 people tomorrow from 10 AM to noon"
                }
                """;

        mockMvc.perform(post("/api/ai/parse-intent")
                        .with(user("member@iitk.ac.in").roles("MEMBER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.minCapacity").value(80))
                .andExpect(jsonPath("$.resourceType").value("Lecture Hall"));
    }

    @Test
    void recommend_whenDateMissing_promptsFollowUp() throws Exception {
        String payload = """
                {
                    "prompt": "I need a lecture hall for 50 people"
                }
                """;

        mockMvc.perform(post("/api/ai/recommend")
                        .with(user("member@iitk.ac.in").roles("MEMBER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.followUpQuestion").isNotEmpty());
    }

    @Test
    void recommend_whenRateLimitExceeded_returns429TooManyRequests() throws Exception {
        String payload = """
                {
                    "prompt": "I need a lecture hall for 50 people on 2026-10-20"
                }
                """;

        // Send 15 valid requests up to the threshold
        for (int i = 0; i < 15; i++) {
            mockMvc.perform(post("/api/ai/recommend")
                            .with(user("spammer@iitk.ac.in").roles("MEMBER"))
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(payload))
                    .andExpect(status().isOk());
        }

        // 16th request within the same minute should be rate limited
        mockMvc.perform(post("/api/ai/recommend")
                        .with(user("spammer@iitk.ac.in").roles("MEMBER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Rate limit exceeded")));
    }
}
