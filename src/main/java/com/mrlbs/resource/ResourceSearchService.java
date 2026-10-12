package com.mrlbs.resource;

import com.mrlbs.booking.Booking;
import com.mrlbs.booking.BookingRepository;
import com.mrlbs.booking.BookingStatus;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

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
        final LocalDateTime start;
        final LocalDateTime end;
        if (startTime != null && endTime != null) {
            start = startTime;
            end = endTime;
        } else if (date != null) {
            start = date.atStartOfDay();
            end = date.plusDays(1).atStartOfDay();
        } else {
            start = null;
            end = null;
        }
        return resourceRepository.findAll(specification).stream()
                .map(resource -> {
                    boolean available = start == null || bookingRepository
                            .findFirstByResourceIdAndStatusAndStartTimeLessThanAndEndTimeGreaterThan(
                                    resource.getId(), BookingStatus.CONFIRMED, end, start).isEmpty();
                    return ResourceSearchResponse.from(resource, available);
                }).toList();
    }

    @Transactional(readOnly = true)
    public ResourceAvailabilityResponse availability(UUID resourceId, LocalDate date) {
        resourceRepository.findById(resourceId).orElseThrow(() -> new IllegalArgumentException("Resource not found"));
        LocalDateTime dayStart = date.atStartOfDay();
        LocalDateTime dayEnd = date.plusDays(1).atStartOfDay();
        List<Booking> bookings = bookingRepository
                .findByResourceIdAndStatusAndStartTimeLessThanAndEndTimeGreaterThanOrderByStartTimeAsc(
                        resourceId, BookingStatus.CONFIRMED, dayEnd, dayStart);
        List<ResourceAvailabilityResponse.TimeSlot> booked = bookings.stream()
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
