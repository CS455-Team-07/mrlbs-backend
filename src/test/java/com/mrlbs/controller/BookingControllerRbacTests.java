package com.mrlbs.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class BookingControllerRbacTests {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void adminCannotSubmitBookingAsMemberEndpoint() throws Exception {
        mockMvc.perform(post("/api/bookings")
                        .with(SecurityMockMvcRequestPostProcessors.user("admin").roles("ADMIN"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "resourceId": "%s",
                                  "startTime": "%s",
                                  "endTime": "%s"
                                }
                                """.formatted(
                                UUID.randomUUID(),
                                LocalDateTime.now().plusDays(1).withHour(10).withMinute(0).withSecond(0).withNano(0),
                                LocalDateTime.now().plusDays(1).withHour(11).withMinute(0).withSecond(0).withNano(0)
                        )))
                .andExpect(status().isForbidden());
    }
}
