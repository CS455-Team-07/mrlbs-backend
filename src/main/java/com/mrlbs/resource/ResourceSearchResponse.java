package com.mrlbs.resource;

import java.math.BigDecimal;
import java.util.UUID;

public record ResourceSearchResponse(UUID id, String name, String type, String location,
                                    Integer capacity, BigDecimal minimumDurationHours,
                                    boolean available, BookingRule bookingRule) {
    public ResourceSearchResponse(UUID id, String name, String type, String location,
                                  Integer capacity, BigDecimal minimumDurationHours,
                                  boolean available) {
        this(id, name, type, location, capacity, minimumDurationHours, available, null);
    }

    public static ResourceSearchResponse from(LabResource resource, boolean available) {
        return new ResourceSearchResponse(resource.getId(), resource.getName(), resource.getType(),
                resource.getLocation(), resource.getCapacity(), resource.getMinimumDurationHours(),
                available, resource.getBookingRule());
    }
}
