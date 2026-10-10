package com.mrlbs.resource;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/resources")
public class ResourceController {

    private final ResourceService resourceService;

    public ResourceController(ResourceService resourceService) {
        this.resourceService = resourceService;
    }

    @GetMapping
    public ResponseEntity<List<Resource>> getAllResources() {
        return ResponseEntity.ok(resourceService.getAllResources());
    }

    @GetMapping("/{resourceId}")
    public ResponseEntity<Resource> getResource(
            @PathVariable Long resourceId
    ) {
        return ResponseEntity.ok(
                resourceService.getResource(resourceId)
        );
    }

    @PutMapping("/{resourceId}")
    public ResponseEntity<Resource> updateResource(
            @PathVariable Long resourceId,
            @RequestBody ResourceUpdateRequest request
    ) {
        return ResponseEntity.ok(
                resourceService.updateResource(resourceId, request)
        );
    }

    @PutMapping("/{resourceId}/booking-rules")
    public ResponseEntity<Resource> updateBookingRules(
            @PathVariable Long resourceId,
            @RequestBody BookingRule bookingRule
    ) {
        return ResponseEntity.ok(
                resourceService.updateBookingRules(
                        resourceId,
                        bookingRule
                )
        );
    }
}