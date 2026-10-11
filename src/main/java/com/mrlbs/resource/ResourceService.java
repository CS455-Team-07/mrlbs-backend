
package com.mrlbs.resource;


import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class ResourceService {

    private final ResourceRepository resourceRepository;
    private final BookingRuleRepository bookingRuleRepository;

    public ResourceService(
            ResourceRepository resourceRepository,
            BookingRuleRepository bookingRuleRepository) {

        this.resourceRepository = resourceRepository;
        this.bookingRuleRepository = bookingRuleRepository;
    }

    // Get all active resources
    public List<Resource> getAllResources() {
        return resourceRepository.findByActiveTrue();
    }

    // Get a resource only if it is active
    public Resource getResource(Long resourceId) {
        Resource resource = resourceRepository.findById(resourceId)
                .orElseThrow(() ->
                        new ResourceNotFoundException(resourceId));

        if (!resource.isActive()) {
            throw new ResourceNotFoundException(resourceId);
        }

        return resource;
    }

    // Get inactive resources for the admin dashboard
    public List<Resource> getInactiveResources() {
        return resourceRepository.findByActiveFalse();
    }

    // Restore a previously deactivated resource
    @Transactional
    public void restoreResource(Long resourceId) {
        Resource resource = resourceRepository.findById(resourceId)
                .orElseThrow(() ->
                        new ResourceNotFoundException(resourceId));

        resource.setActive(true);
        resourceRepository.save(resource);
    }

    // Soft-delete a resource without deleting its historical data
    @Transactional
    public void deactivateResource(Long resourceId) {
        Resource resource = resourceRepository.findById(resourceId)
                .orElseThrow(() ->
                        new ResourceNotFoundException(resourceId));

        resource.setActive(false);
        resourceRepository.save(resource);
    }

    // Update resource details
    @Transactional
    public Resource updateResource(
            Long resourceId,
            ResourceUpdateRequest request) {

        Resource resource = getResource(resourceId);

        if (request.getName() != null
                && !request.getName().isBlank()) {
            resource.setName(request.getName().trim());
        }

        if (request.getType() != null) {
            String type = request.getType().trim();
            resource.setType(type.isEmpty() ? null : type);
        }

        if (request.getLocation() != null) {
            String location = request.getLocation().trim();
            resource.setLocation(
                    location.isEmpty() ? null : location);
        }

        if (request.getCapacity() != null) {
            if (request.getCapacity() < 0) {
                throw new IllegalArgumentException(
                        "Capacity cannot be negative");
            }

            resource.setCapacity(request.getCapacity());
        }

        if (request.getBookingRule() != null) {
            validateRules(request.getBookingRule());
            replaceRulesWithNewVersion(
                    resource, request.getBookingRule());
        }

        return resourceRepository.save(resource);
    }

    // Update booking rules by creating a new rule version
    @Transactional
    public Resource updateBookingRules(
            Long resourceId,
            BookingRule newRules) {

        Resource resource = getResource(resourceId);

        validateRules(newRules);
        replaceRulesWithNewVersion(resource, newRules);

        return resourceRepository.save(resource);
    }

    private void replaceRulesWithNewVersion(
            Resource resource,
            BookingRule supplied) {

        // Preserve the old rule row for historical reference.
        // Do not modify the old rule object.
        BookingRule version = new BookingRule();

        version.setBookingPolicy(supplied.getBookingPolicy());
        version.setMinDurationMinutes(
                supplied.getMinDurationMinutes());
        version.setMaxDurationMinutes(
                supplied.getMaxDurationMinutes());
        version.setBufferTimeMinutes(
                supplied.getBufferTimeMinutes());
        version.setMaxBookingsPerMember(
                supplied.getMaxBookingsPerMember());

        bookingRuleRepository.save(version);
        resource.setBookingRule(version);
    }

    // Validate booking rules
    private void validateRules(BookingRule rules) {

        if (rules.getBookingPolicy() == null) {
            throw new IllegalArgumentException(
                    "Booking policy is required");
        }

        if (rules.getMinDurationMinutes() == null
                || rules.getMinDurationMinutes() <= 0) {
            throw new IllegalArgumentException(
                    "Minimum duration must be greater than 0");
        }

        if (rules.getMaxDurationMinutes() == null
                || rules.getMaxDurationMinutes() <= 0) {
            throw new IllegalArgumentException(
                    "Maximum duration must be greater than 0");
        }

        if (rules.getMinDurationMinutes()
                > rules.getMaxDurationMinutes()) {
            throw new IllegalArgumentException(
                    "Minimum duration cannot be greater "
                    + "than maximum duration");
        }

        if (rules.getBufferTimeMinutes() == null
                || rules.getBufferTimeMinutes() < 0) {
            throw new IllegalArgumentException(
                    "Buffer time cannot be negative");
        }

        if (rules.getMaxBookingsPerMember() == null
                || rules.getMaxBookingsPerMember() <= 0) {
            throw new IllegalArgumentException(
                    "Maximum bookings per member "
                    + "must be greater than 0");
        }
    }

    public static class ResourceNotFoundException
            extends RuntimeException {

        public ResourceNotFoundException(Long id) {
            super("Resource not found with id: " + id);
        }
    }
}
