package com.mrlbs.telemetry.analyzer;

import com.mrlbs.telemetry.client.ExternalTelemetryMeasurement;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Encapsulates the historical telemetry evidence for a specific completed past booking,
 * including individual measurements, coverage metrics, and summary statistics.
 */
public record PastBookingTelemetryRecord(
        UUID bookingId,
        LocalDateTime startTime,
        LocalDateTime endTime,
        String dataQuality, // COMPLETE, PARTIAL, UNAVAILABLE
        double coveragePct,
        boolean hasMissingData,
        String missingDataReason,
        Double avgGpuUtilPct,
        Double peakGpuUtilPct,
        Double avgMemoryUtilPct,
        Double avgTempCelsius,
        Double avgPowerWatts,
        List<ExternalTelemetryMeasurement> measurements,
        int totalMeasurementsCount,
        boolean isDemonstrationData
) {}
