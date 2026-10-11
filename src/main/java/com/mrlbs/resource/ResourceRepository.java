
package com.mrlbs.resource;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ResourceRepository extends JpaRepository<Resource, Long> {

    // Get all active resources
    List<Resource> findByActiveTrue();

    // Get all inactive resources
    List<Resource> findByActiveFalse();

    // Lock a resource while checking and creating a booking.
    // This helps prevent concurrent booking requests from
    // simultaneously passing the availability check.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM Resource r WHERE r.id = :resourceId")
    Optional<Resource> findByIdForUpdate(
            @Param("resourceId") Long resourceId);
}
