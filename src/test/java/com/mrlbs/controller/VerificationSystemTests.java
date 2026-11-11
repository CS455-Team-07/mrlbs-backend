package com.mrlbs.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mrlbs.ai.review.AiReviewStatus;
import com.mrlbs.ai.review.BookingAiReview;
import com.mrlbs.ai.review.BookingAiReviewRepository;
import com.mrlbs.booking.Booking;
import com.mrlbs.booking.BookingRepository;
import com.mrlbs.booking.BookingStatus;
import com.mrlbs.resource.LabResource;
import com.mrlbs.resource.LabResourceRepository;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:mrlbs-verification-system;DB_CLOSE_DELAY=-1",
        "management.health.mail.enabled=false",
        "spring.jpa.properties.hibernate.generate_statistics=true",
        "logging.level.org.hibernate.stat=OFF"
})
@ActiveProfiles("test")
@AutoConfigureMockMvc
class VerificationSystemTests {
    @LocalServerPort int port;
    @Autowired MockMvc mvc;
    @Autowired LabResourceRepository resources;
    @Autowired BookingRepository bookings;
    @Autowired BookingAiReviewRepository reviews;
    @Autowired EntityManagerFactory entities;
    @MockitoBean JavaMailSender mailSender;
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void adminCanReadOnlyReviewsForTheirResourceType() throws Exception {
        LabResource gpu = resources.save(resource("Review test GPU", "GPU"));
        Booking booking = book(gpu, LocalDate.of(2030, 5, 1), BookingStatus.PENDING_APPROVAL);
        BookingAiReview review = new BookingAiReview(booking.getId(), AiReviewStatus.COMPLETED);
        review.setRationale("Scoped telemetry summary");
        reviews.save(review);
        String path = "/api/bookings/" + booking.getId() + "/ai-review";

        mvc.perform(get(path).with(admin("gpus@iitk.ac.in")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rationale").value("Scoped telemetry summary"));
        mvc.perform(get(path).with(admin("lhc@iitk.ac.in")))
                .andExpect(status().isNotFound());
        mvc.perform(get(path).with(oidcLogin().authorities(new SimpleGrantedAuthority("ROLE_MEMBER"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void smtpFailureDoesNotReturnSuccessOrLeakInternalDetails() throws Exception {
        doThrow(new MailSendException("internal SMTP diagnostic")).when(mailSender)
                .send(any(SimpleMailMessage.class));
        mvc.perform(post("/api/auth/forgot-password").with(csrf())
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"new-verification-test@iitk.ac.in\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value("Could not send the verification code. Please try again."));
    }

    @Test
    void searches120ResourcesOverHttpWithOneAvailabilityQueryPerRequest() throws Exception {
        LocalDate date = LocalDate.of(2030, 5, 1);
        List<LabResource> catalog = resources.saveAll(IntStream.range(0, 120)
                .mapToObj(index -> resource("Benchmark " + index, "Benchmark"))
                .toList());
        book(catalog.getFirst(), date, BookingStatus.CONFIRMED);
        book(catalog.getLast(), date, BookingStatus.CANCELLED);
        var statistics = entities.unwrap(SessionFactory.class).getStatistics();

        try (HttpClient http = HttpClient.newHttpClient()) {
            assertThat(request(http, "/api/health").statusCode()).isEqualTo(200);
            String search = "/api/resources/search?type=Benchmark&startTime=" + date + "T10:00:00"
                    + "&endTime=" + date + "T11:00:00";
            for (int warmup = 0; warmup < 3; warmup++) {
                assertThat(request(http, search).statusCode()).isEqualTo(200);
            }
            long[] millis = new long[12];
            long queries = statistics.getQueryExecutionCount();
            for (int index = 0; index < millis.length; index++) {
                long start = System.nanoTime();
                var response = request(http, search);
                millis[index] = (System.nanoTime() - start) / 1_000_000;
                assertThat(response.statusCode()).isEqualTo(200);
                var results = json.readTree(response.body());
                assertThat(results.size()).isEqualTo(120);
                assertThat(results.findValues("available").stream().filter(value -> !value.asBoolean()).count())
                        .isEqualTo(1);
            }
            queries = statistics.getQueryExecutionCount() - queries;
            Arrays.sort(millis);
            System.out.printf("CATALOG_BENCHMARK samples=%d resources=120 queries=%d p50_ms=%d p95_ms=%d%n",
                    millis.length, queries, millis[5], millis[11]);
            assertThat(queries).isEqualTo(millis.length);

            long burstStart = System.nanoTime();
            var burst = IntStream.range(0, 16)
                    .mapToObj(index -> http.sendAsync(httpRequest(search), HttpResponse.BodyHandlers.ofString()))
                    .toList();
            for (var response : burst) {
                var result = response.get(10, java.util.concurrent.TimeUnit.SECONDS);
                assertThat(result.statusCode()).isEqualTo(200);
                assertThat(json.readTree(result.body()).size()).isEqualTo(120);
            }
            System.out.printf("CATALOG_BURST requests=16 resources=120 wall_ms=%d%n",
                    (System.nanoTime() - burstStart) / 1_000_000);
        }
    }

    private HttpResponse<String> request(HttpClient http, String path) throws Exception {
        return http.send(httpRequest(path), HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest httpRequest(String path) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(java.time.Duration.ofSeconds(10)).GET().build();
    }

    private Booking book(LabResource resource, LocalDate date, BookingStatus status) {
        Booking booking = new Booking("member@iitk.ac.in", resource, date.atTime(10, 0), date.atTime(11, 0),
                status, BigDecimal.ZERO);
        booking.setMemberId(UUID.randomUUID());
        return bookings.save(booking);
    }

    private static LabResource resource(String name, String type) {
        return new LabResource(name, type, "Test site", 10, List.of(), BigDecimal.ONE,
                BigDecimal.ONE, BigDecimal.ONE);
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor admin(String email) {
        return oidcLogin().idToken(token -> token.claim("email", email))
                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }
}
