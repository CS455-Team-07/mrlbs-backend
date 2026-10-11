package com.mrlbs.resource;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
                request.rules().stream().map(String::trim).toList());

        return resourceRepository.save(resource);
    }
}
