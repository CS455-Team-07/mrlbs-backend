package com.mrlbs.resource;

import com.mrlbs.booking.Booking;
import com.mrlbs.booking.BookingRepository;
import com.mrlbs.booking.BookingStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
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
        if (startTime == null && endTime == null && date == null) {
            return resources.stream().map(resource -> ResourceSearchResponse.from(resource, true)).toList();
        }

        LocalDateTime now = LocalDateTime.now();
        LocalDate today = now.toLocalDate();
        LocalDateTime nextHour = now.truncatedTo(ChronoUnit.HOURS).plusHours(1);
        boolean hasExactWindow = startTime != null || endTime != null;
        LocalDateTime windowStart;
        LocalDateTime windowEnd;
        if (hasExactWindow) {
            windowStart = startTime != null ? startTime : endTime.minusHours(1);
            windowEnd = endTime != null ? endTime : startTime.plusHours(1);
            if (windowStart.toLocalDate().equals(today) ? windowStart.isBefore(nextHour) : windowEnd.isBefore(now)) {
                return resources.stream().map(resource -> ResourceSearchResponse.from(resource, false)).toList();
            }
        } else {
            windowStart = date.equals(today) ? nextHour : date.atStartOfDay();
            windowEnd = date.plusDays(1).atStartOfDay();
            if (!windowStart.isBefore(windowEnd)) {
                return resources.stream().map(resource -> ResourceSearchResponse.from(resource, false)).toList();
            }
        }

        Map<UUID, List<BookingRepository.AvailabilityWindow>> windowsByResource = bookingRepository
                .findAvailabilityWindows(resources.stream().map(LabResource::getId).toList(),
                        BookingStatus.CONFIRMED, windowStart, windowEnd)
                .stream().collect(Collectors.groupingBy(BookingRepository.AvailabilityWindow::getResourceId));

        return resources.stream().map(resource -> {
            var windows = windowsByResource.getOrDefault(resource.getId(), List.of());
            boolean available = hasExactWindow ? windows.isEmpty() : hasOpenSlot(windows, windowStart, windowEnd);
            return ResourceSearchResponse.from(resource, available);
        }).toList();
    }

    private boolean hasOpenSlot(List<BookingRepository.AvailabilityWindow> bookings,
                                LocalDateTime windowStart, LocalDateTime windowEnd) {
        LocalDateTime cursor = windowStart;
        for (var booking : bookings) {
            LocalDateTime bookingEnd = booking.getEndTime();
            if (!bookingEnd.isAfter(cursor)) {
                continue;
            }
            if (booking.getStartTime().isAfter(cursor)) {
                return true;
            }
            cursor = bookingEnd;
        }
        return cursor.isBefore(windowEnd);
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
            LocalDateTime nextHour = LocalDateTime.now().truncatedTo(ChronoUnit.HOURS).plusHours(1);
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
