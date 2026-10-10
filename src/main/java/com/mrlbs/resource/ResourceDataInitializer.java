
package com.mrlbs.resource;

import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;

@Component
@Profile({"dev", "test", "postgres"})
public class ResourceDataInitializer implements CommandLineRunner {

    private final ResourceRepository resourceRepository;

    public ResourceDataInitializer(ResourceRepository resourceRepository) {
        this.resourceRepository = resourceRepository;
    }

    @Override
    public void run(String... args) {

        // Avoid inserting duplicate sample data.
        if (resourceRepository.count() > 0) {
            return;
        }

        // Resource 1: High-Tier GPU
        createResource(
                "High-Tier GPU",
                "GPU",
                "Computer Centre",
                1,
                BookingPolicy.APPROVAL_REQUIRED,
                60,
                240,
                30,
                2
        );

        // Resource 2: Computer Lab
        createResource(
                "Computer Lab",
                "Laboratory",
                "Block 9",
                60,
                BookingPolicy.OPEN,
                30,
                180,
                15,
                3
        );

        // Resource 3: Lecture Hall
        createResource(
                "Lecture Hall",
                "Lecture Hall",
                "Academic Area",
                120,
                BookingPolicy.APPROVAL_REQUIRED,
                30,
                240,
                15,
                2
        );

        // Additional test resources with varied base rates
        createResourceWithBaseRate(
                "Small Lab",
                "Laboratory",
                "Block 5",
                30,
                BookingPolicy.OPEN,
                15,
                120,
                10,
                1,
                new java.math.BigDecimal("5.00")
        );
        
        createResourceWithBaseRate(
                "Premium GPU",
                "GPU",
                "Computer Centre",
                2,
                BookingPolicy.APPROVAL_REQUIRED,
                60,
                480,
                30,
                2,
                new java.math.BigDecimal("20.00")
        );
        
        System.out.println("Sample resources initialized.");
    }

    private void createResource(
            String name,
            String type,
            String location,
            Integer capacity,
            BookingPolicy policy,
            int minDuration,
            int maxDuration,
            int bufferTime,
            int maxBookingsPerMember
    ) {
        BookingRule rule = new BookingRule();
        rule.setBookingPolicy(policy);
        rule.setMinDurationMinutes(minDuration);
        rule.setMaxDurationMinutes(maxDuration);
        rule.setBufferTimeMinutes(bufferTime);
        rule.setMaxBookingsPerMember(maxBookingsPerMember);

        Resource resource = new Resource();
        resource.setName(name);
        resource.setType(type);
        resource.setLocation(location);
        resource.setCapacity(capacity);
        resource.setBookingRule(rule);
        resource.setBaseRate(new BigDecimal("10.00"));

        // Cascade saves the associated BookingRule too.
        resourceRepository.save(resource);
    }

    /**
     * Helper to create a resource with a custom baseRate.
     */
    private void createResourceWithBaseRate(
            String name,
            String type,
            String location,
            Integer capacity,
            BookingPolicy policy,
            int minDuration,
            int maxDuration,
            int bufferTime,
            int maxBookingsPerMember,
            java.math.BigDecimal baseRate) {
        BookingRule rule = new BookingRule();
        rule.setBookingPolicy(policy);
        rule.setMinDurationMinutes(minDuration);
        rule.setMaxDurationMinutes(maxDuration);
        rule.setBufferTimeMinutes(bufferTime);
        rule.setMaxBookingsPerMember(maxBookingsPerMember);

        Resource resource = new Resource();
        resource.setName(name);
        resource.setType(type);
        resource.setLocation(location);
        resource.setCapacity(capacity);
        resource.setBookingRule(rule);
        resource.setBaseRate(baseRate);
        resource.setMtimeMultiplier(null);
        resourceRepository.save(resource);
    }
}
