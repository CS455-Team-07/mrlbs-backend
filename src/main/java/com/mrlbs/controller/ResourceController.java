package com.mrlbs.controller;

import com.mrlbs.resource.BookingRule;
import com.mrlbs.resource.LabResource;
import com.mrlbs.resource.LabResourceService;
import com.mrlbs.resource.ResourceRequest;
import com.mrlbs.resource.ResourceResponse;
import com.mrlbs.resource.ResourceUpdateRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/resources")
public class ResourceController {

    private final LabResourceService resourceService;

    public ResourceController(LabResourceService resourceService) {
        this.resourceService = resourceService;
    }

    @GetMapping
    public ResponseEntity<List<LabResource>> getAllResources() {
        return ResponseEntity.ok(resourceService.getAllResources());
    }

    @GetMapping("/inactive")
    public ResponseEntity<List<LabResource>> getInactiveResources() {
        return ResponseEntity.ok(resourceService.getInactiveResources());
    }

    @GetMapping("/{resourceId}")
    public ResponseEntity<LabResource> getResource(@PathVariable UUID resourceId) {
        return ResponseEntity.ok(resourceService.getResource(resourceId));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    public ResourceResponse create(@Valid @RequestBody ResourceRequest request) {
        return ResourceResponse.from(resourceService.create(request));
    }

    @PutMapping("/{resourceId}")
    public ResponseEntity<LabResource> updateResource(
            @PathVariable UUID resourceId,
            @RequestBody ResourceUpdateRequest request) {
        return ResponseEntity.ok(resourceService.updateResource(resourceId, request));
    }

    @PutMapping("/{resourceId}/restore")
    public ResponseEntity<Void> restoreResource(@PathVariable UUID resourceId) {
        resourceService.restoreResource(resourceId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{resourceId}")
    public ResponseEntity<Void> deleteResource(@PathVariable UUID resourceId) {
        resourceService.deactivateResource(resourceId);
        return ResponseEntity.noContent().build();
    }
}
