package com.mrlbs.telemetry.client;

import java.time.Instant;

public record ExternalResourceHealth(
    String externalResourceId,
    String healthStatus,
    double gpuUtilizationPct,
    double gpuMemoryUtilizationPct,
    double temperatureCelsius,
    double powerDrawWatts,
    Instant timestamp,
    String dataSource,
    boolean isDemonstrationData
) {}
