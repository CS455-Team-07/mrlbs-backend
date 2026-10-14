package com.mrlbs.telemetry.client;

import java.time.Instant;

public record ExternalTelemetryMeasurement(
    Instant timestamp,
    double gpuUtilizationPct,
    double gpuMemoryUtilizationPct,
    double temperatureCelsius,
    double powerDrawWatts
) {}
