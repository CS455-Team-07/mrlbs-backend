package com.mrlbs.booking;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface BookingRepository extends JpaRepository<Booking, UUID> {
    List<Booking> findByOwnerEmailOrderByStartTimeDesc(String ownerEmail);
}
