package com.mrlbs.controller;

import com.mrlbs.resource.LabResourceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ResourceControllerTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private LabResourceRepository resourceRepository;

    @BeforeEach
    void clearResources() {
        resourceRepository.deleteAll();
    }

    @Test
    void adminCanCreateAndPersistResourceWithInitialRules() throws Exception {
        mockMvc.perform(post("/api/resources")
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "Microscope 4",
                                  "type": "Microscope",
                                  "location": "Science Block, Room 12",
                                  "capacity": 8,
                                  "rules": ["Book for up to two hours", "Wear safety glasses"]
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.name").value("Microscope 4"))
                .andExpect(jsonPath("$.type").value("Microscope"))
                .andExpect(jsonPath("$.location").value("Science Block, Room 12"))
                .andExpect(jsonPath("$.capacity").value(8))
                .andExpect(jsonPath("$.rules", hasSize(2)))
                .andExpect(jsonPath("$.rules[0]").value("Book for up to two hours"));

        org.assertj.core.api.Assertions.assertThat(resourceRepository.count()).isEqualTo(1);
        var resource = resourceRepository.findAll().getFirst();
        org.assertj.core.api.Assertions.assertThat(resource.getName()).isEqualTo("Microscope 4");
        org.assertj.core.api.Assertions.assertThat(resource.getRules())
                .containsExactly("Book for up to two hours", "Wear safety glasses");
    }

    @Test
    void adminCanPersistPricingDurationFloorAndContentionCap() throws Exception {
        mockMvc.perform(post("/api/resources")
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "Laser Cutter",
                                  "type": "Fabrication",
                                  "location": "Workshop 4",
                                  "rules": ["Safety training required"],
                                  "minimumDurationHours": 1.5,
                                  "maximumContentionMultiplier": 2.5
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.minimumDurationHours").value(1.5))
                .andExpect(jsonPath("$.maximumContentionMultiplier").value(2.5));

        var resource = resourceRepository.findAll().getFirst();
        org.assertj.core.api.Assertions.assertThat(resource.getMinimumDurationHours())
                .isEqualByComparingTo("1.5");
        org.assertj.core.api.Assertions.assertThat(resource.getMaximumContentionMultiplier())
                .isEqualByComparingTo("2.5");
    }

    @Test
    void adminCanCreateResourceWithCustomMinMaxDuration() throws Exception {
        mockMvc.perform(post("/api/resources")
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "GPU Server A",
                                  "type": "GPU",
                                  "location": "Server Room 1",
                                  "rules": ["Authorized access only"],
                                  "minDurationMinutes": 45,
                                  "maxDurationMinutes": 240,
                                  "bufferTimeMinutes": 20,
                                  "maxBookingsPerMember": 2
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.bookingRule.minDurationMinutes").value(45))
                .andExpect(jsonPath("$.bookingRule.maxDurationMinutes").value(240))
                .andExpect(jsonPath("$.bookingRule.bufferTimeMinutes").value(20))
                .andExpect(jsonPath("$.bookingRule.maxBookingsPerMember").value(2));

        var resource = resourceRepository.findAll().getFirst();
        org.assertj.core.api.Assertions.assertThat(resource.getBookingRule()).isNotNull();
        org.assertj.core.api.Assertions.assertThat(resource.getBookingRule().getMinDurationMinutes()).isEqualTo(45);
        org.assertj.core.api.Assertions.assertThat(resource.getBookingRule().getMaxDurationMinutes()).isEqualTo(240);
        org.assertj.core.api.Assertions.assertThat(resource.getBookingRule().getBufferTimeMinutes()).isEqualTo(20);
        org.assertj.core.api.Assertions.assertThat(resource.getBookingRule().getMaxBookingsPerMember()).isEqualTo(2);
    }

    @Test
    void memberCannotCreateResource() throws Exception {
        mockMvc.perform(post("/api/resources")
                        .with(user("member").roles("MEMBER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "Microscope 4",
                                  "type": "Microscope",
                                  "location": "Science Block, Room 12",
                                  "capacity": 8,
                                  "rules": ["Book for up to two hours"]
                                }
                                """))
                .andExpect(status().isForbidden());

        org.assertj.core.api.Assertions.assertThat(resourceRepository.count()).isZero();
    }
}
