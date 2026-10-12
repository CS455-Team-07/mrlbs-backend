package com.mrlbs.resource;

import com.mrlbs.booking.BookingRepository;
import com.mrlbs.booking.credit.CreditAccount;
import com.mrlbs.booking.credit.CreditAccountRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

@Component
@Profile({"dev", "postgres"})
public class ResourceDataInitializer implements CommandLineRunner {

    private final LabResourceRepository labResourceRepository;
    private final BookingRepository bookingRepository;
    private final CreditAccountRepository creditAccountRepository;

    public ResourceDataInitializer(LabResourceRepository labResourceRepository,
                                   BookingRepository bookingRepository,
                                   CreditAccountRepository creditAccountRepository) {
        this.labResourceRepository = labResourceRepository;
        this.bookingRepository = bookingRepository;
        this.creditAccountRepository = creditAccountRepository;
    }

    @Override
    @Transactional
    public void run(String... args) {
        // 1. Clear previous resources and dependent bookings
        bookingRepository.deleteAll();
        labResourceRepository.deleteAll();

        // 2. Initialize Member Credit Accounts for Testing Dynamic Credit Pricing & Bookings
        seedCreditAccount("member@iitk.ac.in", new BigDecimal("1000"));
        seedCreditAccount("student@iitk.ac.in", new BigDecimal("1000"));
        seedCreditAccount("admin@iitk.ac.in", new BigDecimal("1000"));

        // 3. Lecture Halls (L1 - L5)
        // Base rate: 0, Min duration: 1 hour, Max multiplier: 3, Approval: No (OPEN), Rule: FCFS
        LabResource l1 = createLectureHall("L1", 100);
        LabResource l2 = createLectureHall("L2", 50);
        LabResource l3 = createLectureHall("L3", 50);
        LabResource l4 = createLectureHall("L4", 50);
        LabResource l5 = createLectureHall("L5", 100);

        // 4. GPU Resources (GPU-A100-01, GPU-H100-01)
        // No capacity limit, Min duration: 1 hour, Max multiplier: 3, Approval: Yes (APPROVAL_REQUIRED), Rule: FCFS
        LabResource gpuA100 = createGpuResource("GPU-A100-01", new BigDecimal("20.00"));
        LabResource gpuH100 = createGpuResource("GPU-H100-01", new BigDecimal("35.00"));

        labResourceRepository.saveAll(List.of(l1, l2, l3, l4, l5, gpuA100, gpuH100));

        System.out.println("Initialized 7 test resources (L1-L5, GPU-A100-01, GPU-H100-01) and credit accounts.");
    }

    private void seedCreditAccount(String email, BigDecimal initialBalance) {
        creditAccountRepository.findById(email).ifPresentOrElse(
                account -> {},
                () -> creditAccountRepository.save(new CreditAccount(email, initialBalance))
        );
    }

    private LabResource createLectureHall(String name, int capacity) {
        LabResource hall = new LabResource(
                name,
                "Lecture Hall",
                "Lecture Hall Complex",
                capacity,
                List.of("First Come, First Served (FCFS)"),
                new BigDecimal("1.00"), // Minimum billable duration: 1 hour
                new BigDecimal("3.00"), // Maximum contention multiplier: 3
                BigDecimal.ZERO         // Base credit rate: 0
        );
        BookingRule rule = new BookingRule();
        rule.setBookingPolicy(BookingPolicy.OPEN); // Admin approval required: No
        rule.setMinDurationMinutes(60);            // 1 hour minimum
        rule.setMaxDurationMinutes(240);
        rule.setBufferTimeMinutes(15);
        rule.setMaxBookingsPerMember(3);
        hall.setBookingRule(rule);
        return hall;
    }

    private LabResource createGpuResource(String name, BigDecimal baseRate) {
        LabResource gpu = new LabResource(
                name,
                "GPU",
                "GPU Lab",
                null,                   // no capacity limit
                List.of("First Come, First Served (FCFS)"),
                new BigDecimal("1.00"), // Minimum billable duration: 1 hour
                new BigDecimal("3.00"), // Maximum contention multiplier: 3
                baseRate
        );
        BookingRule rule = new BookingRule();
        rule.setBookingPolicy(BookingPolicy.APPROVAL_REQUIRED); // Admin approval required: Yes
        rule.setMinDurationMinutes(60);                        // 1 hour minimum
        rule.setMaxDurationMinutes(240);
        rule.setBufferTimeMinutes(30);
        rule.setMaxBookingsPerMember(2);
        gpu.setBookingRule(rule);
        return gpu;
    }
}
