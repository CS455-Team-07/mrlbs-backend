package com.mrlbs.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class InputValidationIntegrationTests {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void authLogin_rejectsInvalidEmailWithStructuredSafeError() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"not-an-email\",\"role\":\"member\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Invalid request"))
                .andExpect(jsonPath("$.detail").value("One or more request fields are invalid."))
                .andExpect(jsonPath("$.errors[0].field").value("email"))
                .andExpect(content().string(not(containsString("Exception"))))
                .andExpect(content().string(not(containsString("stackTrace"))));
    }

    @Test
    void authWorkflowErrors_useTheSameSafeErrorContract() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"member@iitk.ac.in\",\"role\":\"admin\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Invalid request"))
                .andExpect(jsonPath("$.detail").value("The request could not be completed."));

        mockMvc.perform(post("/api/auth/verify-code")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"member@iitk.ac.in\",\"code\":\"123456\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.title").value("Invalid request"))
                .andExpect(jsonPath("$.detail").value("Authentication is required to perform this action."));
    }

    @Test
    void malformedJson_isRejectedWithoutParserDetails() throws Exception {
        mockMvc.perform(post("/api/ai/recommend")
                        .with(user("member@iitk.ac.in").roles("MEMBER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"prompt\":"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("The request body is malformed or contains invalid values."))
                .andExpect(content().string(not(containsString("JsonParseException"))))
                .andExpect(content().string(not(containsString("com.fasterxml"))));
    }

    @Test
    void invalidAiContextDate_isRejectedBeforeRecommendationProcessing() throws Exception {
        mockMvc.perform(post("/api/ai/recommend")
                        .with(user("member@iitk.ac.in").roles("MEMBER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"prompt\":\"Need a GPU\",\"contextDate\":\"2026-02-30\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("The request body is malformed or contains invalid values."))
                .andExpect(content().string(not(containsString("DateTimeException"))));
    }

    @Test
    void invalidQueryAndPathParameters_areRejectedWithSafeErrors() throws Exception {
        mockMvc.perform(get("/api/resources/search?capacity=-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("One or more request parameters are invalid."))
                .andExpect(content().string(not(containsString("ConstraintViolationException"))));

        mockMvc.perform(get("/api/resources/not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("One or more request parameters are invalid."))
                .andExpect(content().string(not(containsString("UUID"))));
    }

    @Test
    void invalidBookingTimeRange_isRejectedBeforeBookingServiceRuns() throws Exception {
        LocalDateTime start = LocalDateTime.now().plusDays(1).withSecond(0).withNano(0);
        LocalDateTime end = start.minusHours(1);

        mockMvc.perform(post("/api/bookings")
                        .with(user("member@iitk.ac.in").roles("MEMBER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "resourceId": "%s",
                                  "startTime": "%s",
                                  "endTime": "%s"
                                }
                                """.formatted(UUID.randomUUID(), start, end)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("timeRangeValid"))
                .andExpect(content().string(not(containsString("BookingService"))));
    }
}
