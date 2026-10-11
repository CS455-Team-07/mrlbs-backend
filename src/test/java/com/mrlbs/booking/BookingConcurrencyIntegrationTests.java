package com.mrlbs.booking;

import com.mrlbs.booking.credit.CreditAccount;
import com.mrlbs.booking.credit.CreditAccountRepository;
import com.mrlbs.resource.LabResource;
import com.mrlbs.resource.ResourceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class BookingConcurrencyIntegrationTests {
    @Autowired private BookingService bookingService;
    @Autowired private BookingRepository bookingRepository;
    @Autowired private ResourceRepository resourceRepository;
    @Autowired private CreditAccountRepository creditAccountRepository;

    @BeforeEach
    void clearData() {
        bookingRepository.deleteAll();
        creditAccountRepository.deleteAll();
        resourceRepository.deleteAll();
    }

    @Test
    void concurrentRequestsForSameSlotConfirmExactlyOneBookingAndDebitOnlyWinner() throws Exception {
        LabResource resource = resourceRepository.save(new LabResource(
                "Shared instrument", "Lab", "Room 1", 1, List.of(),
                BigDecimal.ONE, new BigDecimal("3"), new BigDecimal("4"), false));
        List<String> members = List.of("one@iitk.ac.in", "two@iitk.ac.in");
        members.forEach(email -> creditAccountRepository.save(
                new CreditAccount(email, new BigDecimal("10"))));
        LocalDateTime start = LocalDateTime.now().plusDays(2).withHour(10).withMinute(0).withSecond(0).withNano(0);
        CountDownLatch ready = new CountDownLatch(members.size());
        CountDownLatch begin = new CountDownLatch(1);

        try (var pool = Executors.newFixedThreadPool(members.size())) {
            List<java.util.concurrent.Future<String>> attempts = new ArrayList<>();
            for (String member : members) {
                attempts.add(pool.submit(() -> {
                    ready.countDown();
                    if (!begin.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Timed out waiting to start concurrent booking request");
                    }
                    try {
                        bookingService.submit(member, new BookingRequest(resource.getId(), start,
                                start.plusHours(1)));
                        return "CONFIRMED";
                    } catch (BookingRejectedException rejected) {
                        return rejected.getReason().name();
                    }
                }));
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            begin.countDown();
            List<String> outcomes = new ArrayList<>();
            for (var attempt : attempts) {
                outcomes.add(attempt.get(10, TimeUnit.SECONDS));
            }

            assertThat(outcomes).containsExactlyInAnyOrder("CONFIRMED", "SLOT_NO_LONGER_AVAILABLE");
        }

        assertThat(bookingRepository.count()).isEqualTo(1);
        assertThat(creditAccountRepository.findAll()).extracting(CreditAccount::getBalance)
                .containsExactlyInAnyOrder(new BigDecimal("6"), new BigDecimal("10"));
    }
}
