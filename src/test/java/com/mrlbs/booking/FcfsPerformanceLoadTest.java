package com.mrlbs.booking;

import com.mrlbs.booking.credit.CreditAccount;
import com.mrlbs.booking.credit.CreditAccountRepository;
import com.mrlbs.member.Member;
import com.mrlbs.member.MemberRepository;
import com.mrlbs.resource.BookingPolicy;
import com.mrlbs.resource.LabResource;
import com.mrlbs.resource.LabResourceRepository;
import com.mrlbs.waitlist.WaitlistEntryRepository;
import com.mrlbs.waitlist.WaitlistOfferRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Performance / load test for FCFS booking confirmations.
 *
 * <p><b>Requirement:</b> NFR-1.1 — 95% of FCFS booking confirmations shall
 * complete within 2 seconds end-to-end under a load of at least 100 concurrent
 * booking requests.</p>
 *
 * <p><b>User Story:</b> US-5</p>
 *
 * <p><b>Design:</b></p>
 * <ul>
 *     <li>120 unique members are created, each with their own credit account.</li>
 *     <li>20 lab resources are created with OPEN (FCFS) booking policy and generous
 *         quotas.</li>
 *     <li>6 members target distinct, non-overlapping slots on each resource, so
 *         requests contend on each resource lock while all can finish as CONFIRMED.
 *         Requests go through the HTTP controller, Spring Security,
 *         validation, booking service, and persistence.</li>
 *     <li>A {@link CountDownLatch} barrier ensures all 120 threads fire
 *         simultaneously.</li>
 *     <li>Per-request wall-clock latency is recorded; the 95th-percentile
 *         (p95) value is asserted to be ≤ 2 000 ms.</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public class FcfsPerformanceLoadTest {

    private static final int CONCURRENT_REQUESTS = 120;
    private static final long MAX_P95_MILLIS = 2_000;
    private static final int RESOURCE_COUNT = 20;

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private BookingRepository bookingRepository;
    @Autowired private LabResourceRepository resourceRepository;
    @Autowired private CreditAccountRepository creditAccountRepository;
    @Autowired private MemberRepository memberRepository;
    @Autowired private WaitlistEntryRepository waitlistEntryRepository;
    @Autowired(required = false) private WaitlistOfferRepository offerRepository;

    /** Resources created for this test run. */
    private final List<LabResource> resources = new ArrayList<>();

    /** Emails of the 120 test members. */
    private final List<String> memberEmails = new ArrayList<>();

    // ───────────────────────────────────────────────────────────────
    // Setup
    // ───────────────────────────────────────────────────────────────

    @BeforeEach
    void setUp() {
        // Clean slate
        if (offerRepository != null) offerRepository.deleteAll();
        waitlistEntryRepository.deleteAll();
        bookingRepository.deleteAll();

        resources.clear();
        memberEmails.clear();

        // Create 20 FCFS resources with generous quotas.
        for (int r = 0; r < RESOURCE_COUNT; r++) {
            LabResource resource = new LabResource(
                    "Perf-Resource-" + r,
                    "Lab Bench",
                    "Perf Test Room " + r,
                    1,
                    List.of("Handle equipment carefully"),
                    new BigDecimal("1.00"),   // minimumDurationHours
                    new BigDecimal("3.00"),   // maximumContentionMultiplier
                    new BigDecimal("10.00")   // baseRate
            );
            resource.getBookingRule().setBookingPolicy(BookingPolicy.OPEN);
            resource.getBookingRule().setMinDurationMinutes(30);
            resource.getBookingRule().setMaxDurationMinutes(480);
            resource.getBookingRule().setBufferTimeMinutes(0);
            resource.getBookingRule().setMaxBookingsPerMember(100);
            resources.add(resourceRepository.save(resource));
        }

        // Create 120 unique members, each with a credit account
        for (int i = 0; i < CONCURRENT_REQUESTS; i++) {
            String email = "perf-member-" + i + "@iitk.ac.in";
            memberEmails.add(email);

            if (memberRepository.findByEmail(email).isEmpty()) {
                memberRepository.save(new Member(email, "Perf Member " + i, "MEMBER"));
            }
            creditAccountRepository.findById(email).ifPresentOrElse(
                    acc -> {
                        // Reset balance to a high value
                        BigDecimal topUp = new BigDecimal("100000").subtract(acc.getBalance());
                        if (topUp.signum() > 0) acc.credit(topUp);
                        creditAccountRepository.save(acc);
                    },
                    () -> creditAccountRepository.save(new CreditAccount(email, new BigDecimal("100000")))
            );
        }
    }

    // ───────────────────────────────────────────────────────────────
    // Test
    // ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("NFR-1.1 — 95% of FCFS confirmations complete within 2s under 120 concurrent requests")
    void fcfsBookingConfirmation_p95UnderTwoSeconds() throws Exception {
        // Requests contend on each shared resource row, but each member has a
        // non-overlapping slot. This measures FCFS booking under resource
        // contention while allowing all requests to finish as confirmations.
        LocalDateTime baseDate = LocalDateTime.now().plusDays(10)
                .withHour(8).withMinute(0).withSecond(0).withNano(0);

        ExecutorService executor = Executors.newFixedThreadPool(CONCURRENT_REQUESTS);
        CountDownLatch readyLatch = new CountDownLatch(CONCURRENT_REQUESTS);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(CONCURRENT_REQUESTS);

        List<Long> latencies = Collections.synchronizedList(new ArrayList<>());
        List<BookingStatus> statuses = Collections.synchronizedList(new ArrayList<>());
        List<Exception> errors = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < CONCURRENT_REQUESTS; i++) {
            final int idx = i;
            final String email = memberEmails.get(idx);
            final LabResource resource = resources.get(idx % RESOURCE_COUNT);
            // Members sharing a resource use different hourly slots.
            final int slotOffset = idx / RESOURCE_COUNT;
            final LocalDateTime start = baseDate.plusHours(slotOffset);
            final LocalDateTime end = start.plusHours(1);

            executor.submit(() -> {
                try {
                    readyLatch.countDown();          // signal "I'm ready"
                    startLatch.await();               // wait for the gun

                    long t0 = System.nanoTime();
                    MvcResult result = mockMvc.perform(post("/api/bookings")
                                    .with(SecurityMockMvcRequestPostProcessors.user(email).roles("MEMBER"))
                                    .with(SecurityMockMvcRequestPostProcessors.csrf())
                                    .header("X-User-Email", email)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(objectMapper.writeValueAsBytes(
                                            new BookingRequest(resource.getId(), start, end))))
                            .andReturn();
                    long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);

                    if (result.getResponse().getStatus() == 201) {
                        latencies.add(elapsed);
                        BookingResponse response = objectMapper.readValue(
                                result.getResponse().getContentAsByteArray(), BookingResponse.class);
                        statuses.add(response.status());
                    } else {
                        errors.add(new IllegalStateException("HTTP " + result.getResponse().getStatus()
                                + ": " + result.getResponse().getContentAsString()));
                    }
                } catch (Exception e) {
                    errors.add(e);
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        // Wait until every thread is parked on the barrier, then release them
        try {
            assertTrue(readyLatch.await(30, TimeUnit.SECONDS),
                    "All threads should be ready within 30s");
            startLatch.countDown();

            // Wait for all to finish (generous timeout)
            assertTrue(doneLatch.await(60, TimeUnit.SECONDS),
                    "All booking requests should complete within 60s");
        } finally {
            startLatch.countDown();
            executor.shutdownNow();
        }

        // ── Assertions ──────────────────────────────────────────────

        // 1. Print summary for CI / manual inspection
        int successCount = statuses.size();
        int errorCount = errors.size();
        System.out.println("=== FCFS Performance Load Test Results ===");
        System.out.println("Total requests:    " + CONCURRENT_REQUESTS);
        System.out.println("Successful:        " + successCount);
        System.out.println("Errors:            " + errorCount);

        // 2. At least 100 must have succeeded (the NFR requirement says "100+")
        assertTrue(successCount >= 100,
                "At least 100 requests must succeed; got " + successCount + " successes, "
                        + errorCount + " errors. Sample errors: " + sampleErrors(errors));

        // 3. All successful bookings should be CONFIRMED (FCFS, distinct slots)
        long confirmedCount = statuses.stream()
                .filter(s -> s == BookingStatus.CONFIRMED)
                .count();
        System.out.println("CONFIRMED:         " + confirmedCount);
        assertEquals(successCount, confirmedCount,
                "All successful FCFS bookings on distinct slots should be CONFIRMED");

        // 4. Compute and assert p95 latency
        List<Long> sorted = new ArrayList<>(latencies);
        Collections.sort(sorted);

        long min   = sorted.get(0);
        long max   = sorted.get(sorted.size() - 1);
        long median = sorted.get(sorted.size() / 2);
        long p95   = sorted.get((int) Math.ceil(sorted.size() * 0.95) - 1);
        double avg  = sorted.stream().mapToLong(Long::longValue).average().orElse(0);

        System.out.println("Min latency (ms):  " + min);
        System.out.println("Median (ms):       " + median);
        System.out.println("Avg (ms):          " + String.format("%.1f", avg));
        System.out.println("P95 (ms):          " + p95);
        System.out.println("Max (ms):          " + max);
        System.out.println("==========================================");

        assertTrue(p95 <= MAX_P95_MILLIS,
                "NFR-1.1 violated: P95 latency was " + p95 + " ms, limit is " + MAX_P95_MILLIS + " ms");
    }

    // ───────────────────────────────────────────────────────────────
    // Helpers
    // ───────────────────────────────────────────────────────────────

    private static String sampleErrors(List<Exception> errors) {
        if (errors.isEmpty()) return "none";
        int limit = Math.min(errors.size(), 3);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < limit; i++) {
            if (i > 0) sb.append(" | ");
            sb.append(errors.get(i).getClass().getSimpleName())
              .append(": ").append(errors.get(i).getMessage());
        }
        if (errors.size() > limit) sb.append(" ... and ").append(errors.size() - limit).append(" more");
        return sb.toString();
    }
}
