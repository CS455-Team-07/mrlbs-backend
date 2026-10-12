package com.mrlbs.ai;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

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

    @Test
    void getStatus_returnsAiServiceStatus() throws Exception {
        mockMvc.perform(get("/api/ai/status"))
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
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.followUpQuestion").isNotEmpty());
    }
}
