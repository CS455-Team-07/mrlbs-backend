package com.mrlbs.telemetry;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ResourceTelemetryMappingRepository extends JpaRepository<ResourceTelemetryMapping, UUID> {
    Optional<ResourceTelemetryMapping> findByResourceId(UUID resourceId);
    Optional<ResourceTelemetryMapping> findByExternalResourceId(String externalResourceId);
    boolean existsByResourceId(UUID resourceId);
}
