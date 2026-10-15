package com.mrlbs.resource;

import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.validation.annotation.Validated;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/resources")
@Validated
public class ResourceSearchController {
    private final ResourceSearchService searchService;

    public ResourceSearchController(ResourceSearchService searchService) {
        this.searchService = searchService;
    }

    @GetMapping("/search")
    public List<ResourceSearchResponse> search(
            @RequestParam(required = false) @Size(max = 100) String type,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) java.time.LocalDateTime startTime,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) java.time.LocalDateTime endTime,
            @RequestParam(required = false) @Positive Integer capacity) {
        if (startTime != null && endTime != null && !endTime.isAfter(startTime)) {
            throw new IllegalArgumentException("Invalid search time range");
        }
        return searchService.search(type, date, startTime, endTime, capacity);
    }

    @GetMapping("/{resourceId}/availability")
    public ResourceAvailabilityResponse availability(
            @PathVariable UUID resourceId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return searchService.availability(resourceId, date);
    }
}
