package com.mrlbs.resource;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;
import java.util.UUID;

public interface LabResourceRepository extends JpaRepository<LabResource, UUID>, JpaSpecificationExecutor<LabResource> {
    List<LabResource> findByActiveTrue();
    List<LabResource> findByActiveFalse();
}

