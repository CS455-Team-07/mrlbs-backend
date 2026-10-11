
package com.mrlbs.resource;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController("managedResourceController")
@RequestMapping("/api/resources")
public class ResourceController {

    private final ResourceService resourceService;

    public ResourceController(ResourceService resourceService) {
        this.resourceService = resourceService;
    }

    // Get all active resources
    @GetMapping
    public ResponseEntity<List<Resource>> getAllResources() {
        return ResponseEntity.ok(
                resourceService.getAllResources());
    }

    // Get inactive resources
    @GetMapping("/inactive")
    public ResponseEntity<List<Resource>> getInactiveResources() {
        return ResponseEntity.ok(
                resourceService.getInactiveResources());
    }

    // Restore an inactive resource
    @PutMapping("/{resourceId}/restore")
    public ResponseEntity<Void> restoreResource(
            @PathVariable Long resourceId) {

        resourceService.restoreResource(resourceId);

        return ResponseEntity.noContent().build();
    }

    // Get one active resource
    @GetMapping("/{resourceId}")
    public ResponseEntity<Resource> getResource(
            @PathVariable Long resourceId) {

        return ResponseEntity.ok(
                resourceService.getResource(resourceId));
    }

    // Update resource details
    @PutMapping("/{resourceId}")
    public ResponseEntity<Resource> updateResource(
            @PathVariable Long resourceId,
            @RequestBody ResourceUpdateRequest request) {

        return ResponseEntity.ok(
                resourceService.updateResource(
                        resourceId, request));
    }

    // Update booking rules
    @PutMapping("/{resourceId}/booking-rules")
    public ResponseEntity<Resource> updateBookingRules(
            @PathVariable Long resourceId,
            @RequestBody BookingRule bookingRule) {

        return ResponseEntity.ok(
                resourceService.updateBookingRules(
                        resourceId, bookingRule));
    }

    // Soft-delete a resource
    @DeleteMapping("/{resourceId}")
    public ResponseEntity<Void> deleteResource(
            @PathVariable Long resourceId) {

        resourceService.deactivateResource(resourceId);

        return ResponseEntity.noContent().build();
    }
}
