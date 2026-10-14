package com.mrlbs.telemetry;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "resource_telemetry_mappings")
public class ResourceTelemetryMapping {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "resource_id", nullable = false, unique = true)
    private UUID resourceId;

    @Column(name = "external_resource_id", nullable = false, unique = true, length = 100)
    private String externalResourceId;

    @Column(name = "telemetry_source", nullable = false, length = 64)
    private String telemetrySource = "DEMO_TELEMETRY_SERVICE";

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    public ResourceTelemetryMapping() {}

    public ResourceTelemetryMapping(UUID resourceId, String externalResourceId) {
        this.resourceId = resourceId;
        this.externalResourceId = externalResourceId;
        this.telemetrySource = "DEMO_TELEMETRY_SERVICE";
        this.createdAt = LocalDateTime.now();
    }

    public ResourceTelemetryMapping(UUID resourceId, String externalResourceId, String telemetrySource) {
        this.resourceId = resourceId;
        this.externalResourceId = externalResourceId;
        this.telemetrySource = telemetrySource != null ? telemetrySource : "DEMO_TELEMETRY_SERVICE";
        this.createdAt = LocalDateTime.now();
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getResourceId() {
        return resourceId;
    }

    public void setResourceId(UUID resourceId) {
        this.resourceId = resourceId;
    }

    public String getExternalResourceId() {
        return externalResourceId;
    }

    public void setExternalResourceId(String externalResourceId) {
        this.externalResourceId = externalResourceId;
    }

    public String getTelemetrySource() {
        return telemetrySource;
    }

    public void setTelemetrySource(String telemetrySource) {
        this.telemetrySource = telemetrySource;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
