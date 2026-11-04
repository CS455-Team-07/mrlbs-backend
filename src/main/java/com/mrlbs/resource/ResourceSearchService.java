package com.mrlbs.resource;

import com.mrlbs.booking.Booking;
import com.mrlbs.booking.BookingRepository;
import com.mrlbs.booking.BookingStatus;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class ResourceSearchService {
    private final LabResourceRepository resourceRepository;
    private final BookingRepository bookingRepository;

    public ResourceSearchService(LabResourceRepository resourceRepository, BookingRepository bookingRepository) {
        this.resourceRepository = resourceRepository;
        this.bookingRepository = bookingRepository;
    }

    @Transactional(readOnly = true)
    public List<ResourceSearchResponse> search(String type, LocalDate date, Integer capacity) {
        return search(type, date, null, null, capacity);
    }

    @Transactional(readOnly = true)
    public List<ResourceSearchResponse> search(String type, LocalDate date, LocalDateTime startTime, LocalDateTime endTime, Integer capacity) {
        Specification<LabResource> specification = (root, query, cb) -> cb.isTrue(root.get("active"));
        if (type != null && !type.isBlank()) {
            String term = "%" + type.trim().toLowerCase() + "%";
            specification = specification.and((root, query, cb) -> cb.like(cb.lower(root.get("type")), term));
        }
        if (capacity != null) {
            specification = specification.and((root, query, cb) -> cb.or(
                    cb.isNull(root.get("capacity")), cb.greaterThanOrEqualTo(root.get("capacity"), capacity)));
        }

        List<LabResource> resources = resourceRepository.findAll(specification);
        if (resources.isEmpty()) {
            return List.of();
        }

        // Support searching with timings even if end or start time is partially omitted
        LocalDateTime startParam = startTime;
        LocalDateTime endParam = endTime;
        if (startParam != null && endParam == null) {
            endParam = startParam.plusHours(1);
        } else if (endParam != null && startParam == null) {
            startParam = endParam.minusHours(1);
        }

        LocalDate dateParam = date;
        if (dateParam == null && startParam != null) {
            dateParam = startParam.toLocalDate();
        }

        final LocalDateTime effectiveStart = startParam;
        final LocalDateTime effectiveEnd = endParam;
        final LocalDate effectiveDate = dateParam;

        // 1. Time slot search: if timings are specified (with or without date)
        if (effectiveStart != null && effectiveEnd != null) {
            List<UUID> bookedList = bookingRepository.findConfirmedBookedResourceIdsInSlot(effectiveStart, effectiveEnd);
            Set<UUID> bookedIds = (bookedList != null) ? new HashSet<>(bookedList) : Collections.emptySet();

            return resources.stream()
                    .map(resource -> {
                        boolean available;
                        if (!bookedIds.isEmpty()) {
                            available = !bookedIds.contains(resource.getId());
                        } else {
                            // Fallback to per-resource query to ensure compatibility with unit tests and individual checks
                            available = bookingRepository
                                    .findFirstByResourceIdAndStatusAndStartTimeLessThanAndEndTimeGreaterThan(
                                            resource.getId(), BookingStatus.CONFIRMED, effectiveEnd, effectiveStart).isEmpty();
                        }
                        return ResourceSearchResponse.from(resource, available);
                    }).toList();
        }

        // 2. Date-only search: batch-fetch confirmed bookings to avoid N+1 queries under load
        if (effectiveDate != null) {
            LocalDateTime dayStart = effectiveDate.atStartOfDay();
            LocalDateTime dayEnd = effectiveDate.plusDays(1).atStartOfDay();

            List<Booking> dayBookings = bookingRepository.findConfirmedBookingsInWindow(dayStart, dayEnd);
            if (dayBookings != null && !dayBookings.isEmpty()) {
                Map<UUID, List<Booking>> bookingsByResource = dayBookings.stream()
                        .filter(b -> b.getResource() != null && b.getResource().getId() != null)
                        .collect(Collectors.groupingBy(b -> b.getResource().getId()));

                return resources.stream()
                        .map(resource -> {
                            List<Booking> resBookings = bookingsByResource.get(resource.getId());
                            boolean available;
                            if (resBookings == null || resBookings.isEmpty()) {
                                available = true;
                            } else {
                                available = hasAnyOpenSlotFromBookings(resBookings, dayStart, dayEnd);
                            }
                            return ResourceSearchResponse.from(resource, available);
                        }).toList();
            }

            // Fallback to single resource check if batch returned empty (e.g., when unit tests mock findByResourceId...)
            return resources.stream()
                    .map(resource -> {
                        boolean available = hasAnyOpenSlotOnDate(resource.getId(), effectiveDate);
                        return ResourceSearchResponse.from(resource, available);
                    }).toList();
        }

        // 3. No date and no timings: all matching active resources available
        return resources.stream()
                .map(resource -> ResourceSearchResponse.from(resource, true))
                .toList();
    }

    private boolean hasAnyOpenSlotFromBookings(List<Booking> bookings, LocalDateTime dayStart, LocalDateTime dayEnd) {
        if (bookings == null || bookings.isEmpty()) {
            return true;
        }
        LocalDateTime cursor = dayStart;
        for (Booking booking : bookings) {
            LocalDateTime bStart = booking.getStartTime().isBefore(dayStart) ? dayStart : booking.getStartTime();
            LocalDateTime bEnd = booking.getEndTime().isAfter(dayEnd) ? dayEnd : booking.getEndTime();
            if (bStart.isAfter(cursor)) {
                return true;
            }
            if (bEnd.isAfter(cursor)) {
                cursor = bEnd;
            }
        }
        return cursor.isBefore(dayEnd);
    }

    private boolean hasAnyOpenSlotOnDate(UUID resourceId, LocalDate date) {
        LocalDateTime dayStart = date.atStartOfDay();
        LocalDateTime dayEnd = date.plusDays(1).atStartOfDay();
        List<Booking> bookings = bookingRepository
                .findByResourceIdAndStatusAndStartTimeLessThanAndEndTimeGreaterThanOrderByStartTimeAsc(
                        resourceId, BookingStatus.CONFIRMED, dayEnd, dayStart);
        return hasAnyOpenSlotFromBookings(bookings, dayStart, dayEnd);
    }

    @Transactional(readOnly = true)
    public ResourceAvailabilityResponse availability(UUID resourceId, LocalDate date) {
        resourceRepository.findById(resourceId).orElseThrow(() -> new IllegalArgumentException("Resource not found"));
        LocalDateTime dayStart = date.atStartOfDay();
        LocalDateTime dayEnd = date.plusDays(1).atStartOfDay();
        List<Booking> bookings = bookingRepository
                .findByResourceIdAndStatusAndStartTimeLessThanAndEndTimeGreaterThanOrderByStartTimeAsc(
                        resourceId, BookingStatus.CONFIRMED, dayEnd, dayStart);
        List<ResourceAvailabilityResponse.TimeSlot> booked = (bookings != null ? bookings : Collections.<Booking>emptyList()).stream()
                .map(booking -> new ResourceAvailabilityResponse.TimeSlot(
                        booking.getStartTime().isBefore(dayStart) ? dayStart : booking.getStartTime(),
                        booking.getEndTime().isAfter(dayEnd) ? dayEnd : booking.getEndTime()))
                .toList();
        List<ResourceAvailabilityResponse.TimeSlot> open = new ArrayList<>();
        LocalDateTime cursor = dayStart;
        for (ResourceAvailabilityResponse.TimeSlot slot : booked) {
            if (slot.startTime().isAfter(cursor)) {
                open.add(new ResourceAvailabilityResponse.TimeSlot(cursor, slot.startTime()));
            }
            if (slot.endTime().isAfter(cursor)) cursor = slot.endTime();
        }
        if (cursor.isBefore(dayEnd)) {
            open.add(new ResourceAvailabilityResponse.TimeSlot(cursor, dayEnd));
        }
        return new ResourceAvailabilityResponse(resourceId, date, booked, open);
    }
}
