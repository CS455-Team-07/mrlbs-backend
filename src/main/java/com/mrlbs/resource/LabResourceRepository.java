package com.mrlbs.resource;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.UUID;

public interface LabResourceRepository extends JpaRepository<LabResource, UUID>, JpaSpecificationExecutor<LabResource> {
}
