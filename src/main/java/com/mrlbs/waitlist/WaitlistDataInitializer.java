package com.mrlbs.waitlist;

import com.mrlbs.resource.Resource;
import com.mrlbs.resource.ResourceRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import java.util.List;

/**
 * Seeds sample wait‑list entries for testing the contention multiplier.
 * Runs on the {@code dev} and {@code test} profiles.
 */
@Component
@Profile({"dev", "test", "postgres"})
public class WaitlistDataInitializer implements CommandLineRunner {

    private final WaitlistRepository waitlistRepository;
    private final ResourceRepository resourceRepository;

    public WaitlistDataInitializer(WaitlistRepository waitlistRepository, ResourceRepository resourceRepository) {
        this.waitlistRepository = waitlistRepository;
        this.resourceRepository = resourceRepository;
    }

    @Override
    public void run(String... args) {
        // Avoid duplicate inserts on repeated runs
        if (waitlistRepository.count() > 0) {
            return;
        }

        // Example: pick the first three resources (or create if none exist)
        List<Resource> resources = resourceRepository.findAll();
        if (resources.isEmpty()) {
            return; // No resources to associate with
        }

        // Add 3 wait‑list members to the first resource
        Resource r1 = resources.get(0);
        waitlistRepository.save(new WaitlistEntry(1001L, r1));
        waitlistRepository.save(new WaitlistEntry(1002L, r1));
        waitlistRepository.save(new WaitlistEntry(1003L, r1));

        // Add 7 wait‑list members to the second resource (if exists)
        if (resources.size() > 1) {
            Resource r2 = resources.get(1);
            for (long memberId = 2001L; memberId <= 2007L; memberId++) {
                waitlistRepository.save(new WaitlistEntry(memberId, r2));
            }
        }
    }
}
