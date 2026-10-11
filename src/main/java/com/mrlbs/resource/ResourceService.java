package com.mrlbs.resource;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

@Service
public class ResourceService {

    private final ResourceRepository resourceRepository;

    public ResourceService(ResourceRepository resourceRepository) {
        this.resourceRepository = resourceRepository;
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
                request.baseRate() == null ? BigDecimal.ONE : request.baseRate());

        return resourceRepository.save(resource);
    }
}
