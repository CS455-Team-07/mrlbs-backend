package com.mrlbs.resource;

import java.util.List;
import java.util.UUID;

public record ResourceResponse(UUID id, String name, String type, String location, Integer capacity, List<String> rules) {

    public static ResourceResponse from(LabResource resource) {
        return new ResourceResponse(
                resource.getId(),
                resource.getName(),
                resource.getType(),
                resource.getLocation(),
                resource.getCapacity(),
                resource.getRules());
    }
}
