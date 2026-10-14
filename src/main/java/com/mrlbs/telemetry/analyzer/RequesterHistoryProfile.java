package com.mrlbs.telemetry.analyzer;

import com.mrlbs.telemetry.client.ExternalResourceHealth;

import java.util.List;
import java.util.UUID;

public record RequesterHistoryProfile(
    UUID memberId,
    UUID targetResourceId,
    String externalResourceId,
    int priorBookingsTotal,
    int priorBookingsCompleted,
    int priorBookingsCancelled,
    int priorBookingsRejected,
    int priorBookingsPending,
    Double historicalAvgGpuUtilPct,
    Double historicalPeakGpuUtilPct,
    String historicalTelemetryQuality, // COMPLETE, PARTIAL, UNAVAILABLE, NO_PRIOR_USAGE
    ExternalResourceHealth currentHealth,
    boolean hasHistoricalTelemetry,
    String dataLimitations,
    List<PastBookingTelemetryRecord> pastBookingTelemetryRecords
) {
    // 13-argument backward-compatible constructor for existing tests and call sites
    public RequesterHistoryProfile(
            UUID memberId,
            UUID targetResourceId,
            String externalResourceId,
            int priorBookingsTotal,
            int priorBookingsCompleted,
            int priorBookingsCancelled,
            Double historicalAvgGpuUtilPct,
            Double historicalPeakGpuUtilPct,
            String historicalTelemetryQuality,
            ExternalResourceHealth currentHealth,
            boolean hasHistoricalTelemetry,
            String dataLimitations,
            List<PastBookingTelemetryRecord> pastBookingTelemetryRecords
    ) {
        this(
                memberId,
                targetResourceId,
                externalResourceId,
                priorBookingsTotal,
                priorBookingsCompleted,
                priorBookingsCancelled,
                Math.max(0, priorBookingsTotal - priorBookingsCompleted - priorBookingsCancelled),
                0,
                historicalAvgGpuUtilPct,
                historicalPeakGpuUtilPct,
                historicalTelemetryQuality,
                currentHealth,
                hasHistoricalTelemetry,
                dataLimitations,
                pastBookingTelemetryRecords
        );
    }

    // 12-argument backward-compatible constructor for existing tests
    public RequesterHistoryProfile(
            UUID memberId,
            UUID targetResourceId,
            String externalResourceId,
            int priorBookingsTotal,
            int priorBookingsCompleted,
            int priorBookingsCancelled,
            Double historicalAvgGpuUtilPct,
            Double historicalPeakGpuUtilPct,
            String historicalTelemetryQuality,
            ExternalResourceHealth currentHealth,
            boolean hasHistoricalTelemetry,
            String dataLimitations
    ) {
        this(
                memberId,
                targetResourceId,
                externalResourceId,
                priorBookingsTotal,
                priorBookingsCompleted,
                priorBookingsCancelled,
                Math.max(0, priorBookingsTotal - priorBookingsCompleted - priorBookingsCancelled),
                0,
                historicalAvgGpuUtilPct,
                historicalPeakGpuUtilPct,
                historicalTelemetryQuality,
                currentHealth,
                hasHistoricalTelemetry,
                dataLimitations,
                List.of()
        );
    }
}
