package com.mrlbs.resource;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;
import java.util.UUID;

public interface LabResourceRepository extends JpaRepository<LabResource, UUID>, JpaSpecificationExecutor<LabResource> {
    @org.springframework.data.jpa.repository.Query("SELECT DISTINCT r FROM LabResource r LEFT JOIN FETCH r.bookingRule WHERE r.active = true")
    List<LabResource> findByActiveTrue();
    List<LabResource> findByActiveFalse();
}

