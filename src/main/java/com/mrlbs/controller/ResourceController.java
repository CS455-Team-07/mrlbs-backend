package com.mrlbs.controller;

import com.mrlbs.resource.BookingRule;
import com.mrlbs.resource.LabResource;
import com.mrlbs.resource.LabResourceService;
import com.mrlbs.resource.ResourceRequest;
import com.mrlbs.resource.ResourceResponse;
import com.mrlbs.resource.ResourceUpdateRequest;
import com.mrlbs.security.AdminResourceScope;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
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

    /** Returns active resources, filtered to the caller's scope when they are a scoped admin. */
    @GetMapping
    public ResponseEntity<List<LabResource>> getAllResources(
            @AuthenticationPrincipal OidcUser user) {
        List<LabResource> all = resourceService.getAllResources();
        if (user != null) {
            String email = user.getEmail();
            return AdminResourceScope.scopeForEmail(email)
                    .map(scope -> ResponseEntity.ok(
                            all.stream().filter(r -> scope.equalsIgnoreCase(r.getType())).toList()))
                    .orElseGet(() -> ResponseEntity.ok(all));
        }
        return ResponseEntity.ok(all);
    }

    @GetMapping("/inactive")
    public ResponseEntity<List<LabResource>> getInactiveResources(
            @AuthenticationPrincipal OidcUser user) {
        List<LabResource> all = resourceService.getInactiveResources();
        if (user != null) {
            String email = user.getEmail();
            return AdminResourceScope.scopeForEmail(email)
                    .map(scope -> ResponseEntity.ok(
                            all.stream().filter(r -> scope.equalsIgnoreCase(r.getType())).toList()))
                    .orElseGet(() -> ResponseEntity.ok(all));
        }
        return ResponseEntity.ok(all);
    }

    @GetMapping("/{resourceId}")
    public ResponseEntity<LabResource> getResource(@PathVariable UUID resourceId) {
        return ResponseEntity.ok(resourceService.getResource(resourceId));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    public ResourceResponse create(
            @Valid @RequestBody ResourceRequest request,
            @AuthenticationPrincipal OidcUser user) {
        validateScopeForType(user, request.type());
        return ResourceResponse.from(resourceService.create(request));
    }

    @PutMapping("/{resourceId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<LabResource> updateResource(
            @PathVariable UUID resourceId,
            @RequestBody ResourceUpdateRequest request,
            @AuthenticationPrincipal OidcUser user) {
        LabResource existing = resourceService.getResource(resourceId);
        validateScopeForType(user, existing.getType());
        return ResponseEntity.ok(resourceService.updateResource(resourceId, request));
    }

    @PutMapping("/{resourceId}/restore")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> restoreResource(
            @PathVariable UUID resourceId,
            @AuthenticationPrincipal OidcUser user) {
        // For inactive resources we must look them up differently
        LabResource existing = resourceService.getResourceIncludingInactive(resourceId);
        validateScopeForType(user, existing.getType());
        resourceService.restoreResource(resourceId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{resourceId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> deleteResource(
            @PathVariable UUID resourceId,
            @AuthenticationPrincipal OidcUser user) {
        LabResource existing = resourceService.getResource(resourceId);
        validateScopeForType(user, existing.getType());
        resourceService.deactivateResource(resourceId);
        return ResponseEntity.noContent().build();
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /**
     * Throws {@link AccessDeniedException} when the authenticated user is a
     * scoped admin who does not own {@code resourceType}.  Super-admins (no
     * scope) pass through unchecked.
     */
    private void validateScopeForType(OidcUser user, String resourceType) {
        if (user == null) return;
        AdminResourceScope.scopeForEmail(user.getEmail()).ifPresent(scope -> {
            if (!scope.equalsIgnoreCase(resourceType)) {
                throw new AccessDeniedException(
                        "You are only authorised to manage resources of type: " + scope);
            }
        });
    }
}
