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

    private volatile List<LabResource> cachedActiveResources = null;
    private volatile long lastResourceCacheTime = 0L;
    private static final long CACHE_TTL_MS = 30_000L; // 30 seconds

    public ResourceSearchService(LabResourceRepository resourceRepository, BookingRepository bookingRepository) {
        this.resourceRepository = resourceRepository;
        this.bookingRepository = bookingRepository;
    }

    public void invalidateResourceCache() {
        this.cachedActiveResources = null;
        this.lastResourceCacheTime = 0L;
    }

    @Transactional(readOnly = true)
    public List<ResourceSearchResponse> search(String type, LocalDate date, Integer capacity) {
        return search(type, date, null, null, capacity);
    }

    @Transactional(readOnly = true)
    public List<ResourceSearchResponse> search(String type, LocalDate date, LocalDateTime startTime, LocalDateTime endTime, Integer capacity) {
        List<LabResource> resources = getFilteredActiveResources(type, capacity);
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
            // Cannot book past slots or slots starting earlier than next hour for today
            if (effectiveStart.toLocalDate().equals(LocalDate.now())) {
                LocalDateTime nextHour = LocalDateTime.now().truncatedTo(java.time.temporal.ChronoUnit.HOURS).plusHours(1);
                if (effectiveStart.isBefore(nextHour)) {
                    return resources.stream()
                            .map(resource -> ResourceSearchResponse.from(resource, false))
                            .toList();
                }
            } else if (effectiveEnd.isBefore(LocalDateTime.now())) {
                return resources.stream()
                        .map(resource -> ResourceSearchResponse.from(resource, false))
                        .toList();
            }

            List<UUID> bookedList = bookingRepository.findConfirmedBookedResourceIdsInSlot(effectiveStart, effectiveEnd);
            Set<UUID> bookedIds = (bookedList != null) ? new HashSet<>(bookedList) : Collections.emptySet();

            return resources.stream()
                    .map(resource -> ResourceSearchResponse.from(resource, !bookedIds.contains(resource.getId())))
                    .toList();
        }

        // 2. Date-only search: batch-fetch confirmed bookings to avoid N+1 queries under load
        if (effectiveDate != null) {
            LocalDateTime dayStart = effectiveDate.atStartOfDay();
            LocalDateTime dayEnd = effectiveDate.plusDays(1).atStartOfDay();

            // For current day, slots start from the next full hour from now
            LocalDateTime slotWindowStart = dayStart;
            if (effectiveDate.equals(LocalDate.now())) {
                LocalDateTime nextHour = LocalDateTime.now().truncatedTo(java.time.temporal.ChronoUnit.HOURS).plusHours(1);
                if (nextHour.isAfter(dayStart)) {
                    slotWindowStart = nextHour;
                }
            }

            if (!slotWindowStart.isBefore(dayEnd)) {
                // All hours for today have already passed
                return resources.stream()
                        .map(resource -> ResourceSearchResponse.from(resource, false))
                        .toList();
            }

            final LocalDateTime effectiveSearchStart = slotWindowStart;
            List<Booking> dayBookings = bookingRepository.findConfirmedBookingsInWindow(dayStart, dayEnd);
            Map<UUID, List<Booking>> bookingsByResource = (dayBookings != null && !dayBookings.isEmpty())
                    ? dayBookings.stream()
                            .filter(b -> b.getResource() != null && b.getResource().getId() != null)
                            .collect(Collectors.groupingBy(b -> b.getResource().getId()))
                    : Collections.emptyMap();

            return resources.stream()
                    .map(resource -> {
                        List<Booking> resBookings = bookingsByResource.get(resource.getId());
                        boolean available = hasAnyOpenSlotFromBookings(resBookings, effectiveSearchStart, dayEnd);
                        return ResourceSearchResponse.from(resource, available);
                    }).toList();
        }

        // 3. No date and no timings: all matching active resources available
        return resources.stream()
                .map(resource -> ResourceSearchResponse.from(resource, true))
                .toList();
    }

    private boolean hasAnyOpenSlotFromBookings(List<Booking> bookings, LocalDateTime dayStart, LocalDateTime dayEnd) {
        if (!dayStart.isBefore(dayEnd)) {
            return false;
        }
        if (bookings == null || bookings.isEmpty()) {
            return true;
        }
        LocalDateTime cursor = dayStart;
        for (Booking booking : bookings) {
            LocalDateTime bStart = booking.getStartTime().isBefore(dayStart) ? dayStart : booking.getStartTime();
            LocalDateTime bEnd = booking.getEndTime().isAfter(dayEnd) ? dayEnd : booking.getEndTime();
            if (bEnd.isBefore(cursor) || bEnd.isEqual(cursor)) {
                continue;
            }
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
        LocalDateTime slotWindowStart = dayStart;
        if (date.equals(LocalDate.now())) {
            LocalDateTime nextHour = LocalDateTime.now().truncatedTo(java.time.temporal.ChronoUnit.HOURS).plusHours(1);
            if (nextHour.isAfter(dayStart)) {
                slotWindowStart = nextHour;
            }
        }
        List<Booking> bookings = bookingRepository
                .findByResourceIdAndStatusAndStartTimeLessThanAndEndTimeGreaterThanOrderByStartTimeAsc(
                        resourceId, BookingStatus.CONFIRMED, dayEnd, dayStart);
        return hasAnyOpenSlotFromBookings(bookings, slotWindowStart, dayEnd);
    }

    private List<LabResource> getFilteredActiveResources(String type, Integer capacity) {
        long now = System.currentTimeMillis();
        List<LabResource> allActive = cachedActiveResources;
        if (allActive == null || (now - lastResourceCacheTime > CACHE_TTL_MS)) {
            allActive = resourceRepository.findAll((root, query, cb) -> cb.isTrue(root.get("active")));
            cachedActiveResources = allActive;
            lastResourceCacheTime = now;
        }

        if (allActive == null || allActive.isEmpty()) {
            return List.of();
        }

        final String searchType = (type != null && !type.isBlank()) ? type.trim().toLowerCase() : null;
        return allActive.stream()
                .filter(r -> Boolean.TRUE.equals(r.isActive()))
                .filter(r -> searchType == null || (r.getType() != null && r.getType().toLowerCase().contains(searchType)))
                .filter(r -> capacity == null || (r.getCapacity() == null || r.getCapacity() >= capacity))
                .toList();
    }

    @Transactional(readOnly = true)
    public ResourceAvailabilityResponse availability(UUID resourceId, LocalDate date) {
        resourceRepository.findById(resourceId).orElseThrow(() -> new IllegalArgumentException("Resource not found"));
        LocalDateTime dayStart = date.atStartOfDay();
        LocalDateTime dayEnd = date.plusDays(1).atStartOfDay();

        // For present day, available slots start from the next full hour
        LocalDateTime effectiveOpenStart = dayStart;
        if (date.equals(LocalDate.now())) {
            LocalDateTime nextHour = LocalDateTime.now().truncatedTo(java.time.temporal.ChronoUnit.HOURS).plusHours(1);
            if (nextHour.isAfter(dayStart)) {
                effectiveOpenStart = nextHour;
            }
        }

        List<Booking> bookings = bookingRepository
                .findByResourceIdAndStatusAndStartTimeLessThanAndEndTimeGreaterThanOrderByStartTimeAsc(
                        resourceId, BookingStatus.CONFIRMED, dayEnd, dayStart);
        List<ResourceAvailabilityResponse.TimeSlot> booked = (bookings != null ? bookings : Collections.<Booking>emptyList()).stream()
                .map(booking -> new ResourceAvailabilityResponse.TimeSlot(
                        booking.getStartTime().isBefore(dayStart) ? dayStart : booking.getStartTime(),
                        booking.getEndTime().isAfter(dayEnd) ? dayEnd : booking.getEndTime()))
                .toList();

        List<ResourceAvailabilityResponse.TimeSlot> open = new ArrayList<>();
        if (effectiveOpenStart.isBefore(dayEnd)) {
            LocalDateTime cursor = effectiveOpenStart;
            for (ResourceAvailabilityResponse.TimeSlot slot : booked) {
                if (slot.endTime().isBefore(cursor) || slot.endTime().isEqual(cursor)) {
                    continue; // Booking is already in the past
                }
                LocalDateTime slotStart = slot.startTime().isBefore(cursor) ? cursor : slot.startTime();
                if (slotStart.isAfter(cursor)) {
                    open.add(new ResourceAvailabilityResponse.TimeSlot(cursor, slotStart));
                }
                if (slot.endTime().isAfter(cursor)) {
                    cursor = slot.endTime();
                }
            }
            if (cursor.isBefore(dayEnd)) {
                open.add(new ResourceAvailabilityResponse.TimeSlot(cursor, dayEnd));
            }
        }
        return new ResourceAvailabilityResponse(resourceId, date, booked, open);
    }
}
