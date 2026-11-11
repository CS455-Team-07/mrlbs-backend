package com.mrlbs.resource;

import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;
import java.util.UUID;

public interface LabResourceRepository extends JpaRepository<LabResource, UUID>, JpaSpecificationExecutor<LabResource> {
    @Override
    @EntityGraph(attributePaths = "bookingRule")
    List<LabResource> findAll(Specification<LabResource> specification);

    @org.springframework.data.jpa.repository.Query("SELECT DISTINCT r FROM LabResource r LEFT JOIN FETCH r.bookingRule WHERE r.active = true")
    List<LabResource> findByActiveTrue();
    List<LabResource> findByActiveFalse();
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("SELECT r FROM LabResource r WHERE r.id = :id")
    java.util.Optional<LabResource> findByIdForUpdate(@org.springframework.data.repository.query.Param("id") UUID id);
}

