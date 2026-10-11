package com.mrlbs.resource;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

import java.util.UUID;

public interface ResourceRepository extends JpaRepository<LabResource, UUID>, JpaSpecificationExecutor<LabResource> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select resource from LabResource resource where resource.id = :id")
    java.util.Optional<LabResource> findByIdForUpdate(@Param("id") UUID id);
}
