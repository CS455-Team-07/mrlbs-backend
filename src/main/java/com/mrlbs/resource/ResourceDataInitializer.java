
package com.mrlbs.resource;

import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("dev")
public class ResourceDataInitializer implements CommandLineRunner {

    private final ResourceRepository resourceRepository;

    public ResourceDataInitializer(ResourceRepository resourceRepository) {
        this.resourceRepository = resourceRepository;
    }

    @Override
    public void run(String... args) {
        // Avoid inserting duplicate sample resources.
        if (resourceRepository.count() > 0) {
            return;
        }

        // 1. High-Tier GPU
        Resource gpu = createResource(
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

        // 2. Computer Lab
        Resource lab = createResource(
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

        // 3. Lecture Hall
        Resource lectureHall = createResource(
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

        // BookingRule is persisted through Resource's cascade mapping.
        resourceRepository.save(gpu);
        resourceRepository.save(lab);
        resourceRepository.save(lectureHall);

        System.out.println("Sample resources initialized successfully.");
    }

    private Resource createResource(
            String name,
            String type,
            String location,
            int capacity,
            BookingPolicy bookingPolicy,
            int minDurationMinutes,
            int maxDurationMinutes,
            int bufferTimeMinutes,
            int maxBookingsPerMember
    ) {
        BookingRule rule = new BookingRule();
        rule.setBookingPolicy(bookingPolicy);
        rule.setMinDurationMinutes(minDurationMinutes);
        rule.setMaxDurationMinutes(maxDurationMinutes);
        rule.setBufferTimeMinutes(bufferTimeMinutes);
        rule.setMaxBookingsPerMember(maxBookingsPerMember);

        Resource resource = new Resource();
        resource.setName(name);
        resource.setType(type);
        resource.setLocation(location);
        resource.setCapacity(capacity);
        resource.setBookingRule(rule);

        return resource;
    }
}
