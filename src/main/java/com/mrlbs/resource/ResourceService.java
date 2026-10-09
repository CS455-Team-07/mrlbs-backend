package com.mrlbs.resource;

import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ResourceService {

    private final ResourceRepository resourceRepository;

    public ResourceService(ResourceRepository resourceRepository) {
        this.resourceRepository = resourceRepository;
    }

    public List<Resource> getAllResources() {
        return resourceRepository.findAll();
    }

    public Resource getResource(Long resourceId) {
        return resourceRepository.findById(resourceId)
                .orElseThrow(() ->
                        new RuntimeException("Resource not found with id: " + resourceId));
    }

    public Resource updateBookingRules(Long resourceId, BookingRule newRules) {

        validateRules(newRules);

        Resource resource = getResource(resourceId);

        resource.setBookingRule(newRules);

        return resourceRepository.save(resource);
    }

    private void validateRules(BookingRule rules) {

        if (rules.getBookingPolicy() == null) {
            throw new IllegalArgumentException(
                    "Booking policy is required"
            );
        }

        if (rules.getMinDurationMinutes() <= 0) {
            throw new IllegalArgumentException(
                    "Minimum duration must be greater than 0"
            );
        }

        if (rules.getMaxDurationMinutes() <= 0) {
            throw new IllegalArgumentException(
                    "Maximum duration must be greater than 0"
            );
        }

        if (rules.getMinDurationMinutes() > rules.getMaxDurationMinutes()) {
            throw new IllegalArgumentException(
                    "Minimum duration cannot be greater than maximum duration"
            );
        }

        if (rules.getBufferTimeMinutes() < 0) {
            throw new IllegalArgumentException(
                    "Buffer time cannot be negative"
            );
        }

        if (rules.getMaxBookingsPerMember() <= 0) {
            throw new IllegalArgumentException(
                    "Maximum bookings per member must be greater than 0"
            );
        }
    }
}