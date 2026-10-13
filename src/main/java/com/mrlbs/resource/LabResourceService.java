package com.mrlbs.resource;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Service
public class LabResourceService {

    private final LabResourceRepository resourceRepository;
    private final BookingRuleRepository bookingRuleRepository;

    public LabResourceService(LabResourceRepository resourceRepository,
                              BookingRuleRepository bookingRuleRepository) {
        this.resourceRepository = resourceRepository;
        this.bookingRuleRepository = bookingRuleRepository;
    }

    @Transactional
    public LabResource create(ResourceRequest request) {
        LabResource resource = new LabResource(
                request.name().trim(),
                request.type().trim(),
                request.location().trim(),
                request.capacity(),
                request.rules().stream().map(String::trim).toList(),
                request.minimumDurationHours() == null ? new BigDecimal("1.00") : request.minimumDurationHours(),
                request.maximumContentionMultiplier() == null ? new BigDecimal("3.00") : request.maximumContentionMultiplier(),
                request.baseRate() == null ? BigDecimal.ZERO : request.baseRate());

        BookingRule rule = request.bookingRule();
        if (rule == null) {
            if (request.minDurationMinutes() != null || request.maxDurationMinutes() != null
                    || request.approvalRequired() != null || request.bufferTimeMinutes() != null
                    || request.maxBookingsPerMember() != null) {
                rule = new BookingRule();
                BookingPolicy policy = Boolean.TRUE.equals(request.approvalRequired())
                        ? BookingPolicy.APPROVAL_REQUIRED
                        : BookingPolicy.FCFS;
                rule.setBookingPolicy(policy);
                rule.setMinDurationMinutes(request.minDurationMinutes() != null ? request.minDurationMinutes() : 30);
                rule.setMaxDurationMinutes(request.maxDurationMinutes() != null ? request.maxDurationMinutes() : 180);
                rule.setBufferTimeMinutes(request.bufferTimeMinutes() != null ? request.bufferTimeMinutes() : 15);
                rule.setMaxBookingsPerMember(request.maxBookingsPerMember() != null ? request.maxBookingsPerMember() : 3);
            }
        }

        if (rule != null) {
            if (rule.getBookingPolicy() == null) {
                rule.setBookingPolicy(Boolean.TRUE.equals(request.approvalRequired()) ? BookingPolicy.APPROVAL_REQUIRED : BookingPolicy.FCFS);
            }
            if (rule.getMinDurationMinutes() == null) {
                rule.setMinDurationMinutes(request.minDurationMinutes() != null ? request.minDurationMinutes() : 30);
            }
            if (rule.getMaxDurationMinutes() == null) {
                rule.setMaxDurationMinutes(request.maxDurationMinutes() != null ? request.maxDurationMinutes() : 180);
            }
            if (rule.getBufferTimeMinutes() == null) {
                rule.setBufferTimeMinutes(request.bufferTimeMinutes() != null ? request.bufferTimeMinutes() : 15);
            }
            if (rule.getMaxBookingsPerMember() == null) {
                rule.setMaxBookingsPerMember(request.maxBookingsPerMember() != null ? request.maxBookingsPerMember() : 3);
            }

            validateRules(rule);
            BookingRule savedRule = bookingRuleRepository.save(rule);
            resource.setBookingRule(savedRule);
        }

        return resourceRepository.save(resource);
    }

    @Transactional(readOnly = true)
    public List<LabResource> findAll() {
        return resourceRepository.findAll();
    }

    @Transactional(readOnly = true)
    public List<LabResource> getAllResources() {
        return resourceRepository.findByActiveTrue();
    }

    @Transactional(readOnly = true)
    public List<LabResource> getInactiveResources() {
        return resourceRepository.findByActiveFalse();
    }

    @Transactional(readOnly = true)
    public LabResource getResource(UUID resourceId) {
        LabResource resource = resourceRepository.findById(resourceId)
                .orElseThrow(() -> new ResourceNotFoundException(resourceId));

        if (!resource.isActive()) {
            throw new ResourceNotFoundException(resourceId);
        }

        return resource;
    }

    @Transactional(readOnly = true)
    public LabResource getResourceIncludingInactive(UUID resourceId) {
        return resourceRepository.findById(resourceId)
                .orElseThrow(() -> new ResourceNotFoundException(resourceId));
    }

    @Transactional
    public void restoreResource(UUID resourceId) {
        LabResource resource = resourceRepository.findById(resourceId)
                .orElseThrow(() -> new ResourceNotFoundException(resourceId));

        resource.setActive(true);
        resourceRepository.save(resource);
    }

    @Transactional
    public void deactivateResource(UUID resourceId) {
        LabResource resource = resourceRepository.findById(resourceId)
                .orElseThrow(() -> new ResourceNotFoundException(resourceId));

        resource.setActive(false);
        resourceRepository.save(resource);
    }

    @Transactional
    public LabResource updateResource(UUID resourceId, ResourceUpdateRequest request) {
        LabResource resource = getResource(resourceId);

        if (request.getName() != null && !request.getName().isBlank()) {
            resource.setName(request.getName().trim());
        }

        if (request.getType() != null) {
            String type = request.getType().trim();
            resource.setType(type.isEmpty() ? null : type);
        }

        if (request.getLocation() != null) {
            String location = request.getLocation().trim();
            resource.setLocation(location.isEmpty() ? null : location);
        }

        if (request.getCapacity() != null) {
            if (request.getCapacity() < 0) {
                throw new IllegalArgumentException("Capacity cannot be negative");
            }
            resource.setCapacity(request.getCapacity());
        }

        if (request.getBookingRule() != null) {
            validateRules(request.getBookingRule());
            BookingRule supplied = request.getBookingRule();
            BookingRule version = new BookingRule();
            version.setBookingPolicy(supplied.getBookingPolicy());
            version.setMinDurationMinutes(supplied.getMinDurationMinutes());
            version.setMaxDurationMinutes(supplied.getMaxDurationMinutes());
            version.setBufferTimeMinutes(supplied.getBufferTimeMinutes());
            version.setMaxBookingsPerMember(supplied.getMaxBookingsPerMember());

            bookingRuleRepository.save(version);
            resource.setBookingRule(version);
        }

        return resourceRepository.save(resource);
    }

    private void validateRules(BookingRule rules) {
        if (rules.getBookingPolicy() == null) {
            throw new IllegalArgumentException("Booking policy is required");
        }

        if (rules.getMinDurationMinutes() == null || rules.getMinDurationMinutes() <= 0) {
            throw new IllegalArgumentException("Minimum duration must be greater than 0");
        }

        if (rules.getMaxDurationMinutes() == null || rules.getMaxDurationMinutes() <= 0) {
            throw new IllegalArgumentException("Maximum duration must be greater than 0");
        }

        if (rules.getMinDurationMinutes() > rules.getMaxDurationMinutes()) {
            throw new IllegalArgumentException("Minimum duration cannot be greater than maximum duration");
        }

        if (rules.getBufferTimeMinutes() == null || rules.getBufferTimeMinutes() < 0) {
            throw new IllegalArgumentException("Buffer time cannot be negative");
        }

        if (rules.getMaxBookingsPerMember() == null || rules.getMaxBookingsPerMember() <= 0) {
            throw new IllegalArgumentException("Maximum bookings per member must be greater than 0");
        }
    }

    public static class ResourceNotFoundException extends RuntimeException {
        public ResourceNotFoundException(UUID id) {
            super("Resource not found with id: " + id);
        }

        public ResourceNotFoundException(String id) {
            super("Resource not found with id: " + id);
        }
    }
}
