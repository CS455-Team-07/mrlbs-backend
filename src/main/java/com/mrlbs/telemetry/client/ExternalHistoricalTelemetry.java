package com.mrlbs.telemetry.client;

import java.time.Instant;
import java.util.List;

public record ExternalHistoricalTelemetry(
    String externalResourceId,
    Instant queryStartTime,
    Instant queryEndTime,
    String dataQuality, // COMPLETE, PARTIAL, UNAVAILABLE
    double coveragePct,
    boolean isDemonstrationData,
    String dataSource,
    List<ExternalTelemetryMeasurement> measurements
) {}
