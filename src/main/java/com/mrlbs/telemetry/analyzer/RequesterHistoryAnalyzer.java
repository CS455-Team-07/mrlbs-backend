package com.mrlbs.telemetry.analyzer;

import com.mrlbs.booking.Booking;
import com.mrlbs.booking.BookingRepository;
import com.mrlbs.booking.BookingStatus;
import com.mrlbs.telemetry.ResourceTelemetryMapping;
import com.mrlbs.telemetry.ResourceTelemetryMappingRepository;
import com.mrlbs.telemetry.client.ExternalHistoricalTelemetry;
import com.mrlbs.telemetry.client.ExternalResourceHealth;
import com.mrlbs.telemetry.client.ExternalTelemetryMeasurement;
import com.mrlbs.telemetry.client.ResourceTelemetryClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service
public class RequesterHistoryAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(RequesterHistoryAnalyzer.class);

    private static final String DATA_LIMITATIONS_DISCLOSURE =
            "Telemetry reflects device-level measurements during exclusive reservation window. " +
            "Individual job-level attribution is not captured by MRLBS. " +
            "Current health reflects present operating status only and does not guarantee future availability.";

    private final BookingRepository bookingRepository;
    private final ResourceTelemetryMappingRepository mappingRepository;
    private final ResourceTelemetryClient telemetryClient;

    public RequesterHistoryAnalyzer(
            BookingRepository bookingRepository,
            ResourceTelemetryMappingRepository mappingRepository,
            ResourceTelemetryClient telemetryClient
    ) {
        this.bookingRepository = bookingRepository;
        this.mappingRepository = mappingRepository;
        this.telemetryClient = telemetryClient;
    }

    public RequesterHistoryProfile analyzeRequesterHistory(
            UUID memberId,
            UUID targetResourceId,
            UUID currentBookingId
    ) {
        // 1. Fetch all bookings for member and filter out the pending booking currently under review
        List<Booking> allMemberBookings = bookingRepository.findByMemberIdOrderByStartTimeDesc(memberId);

        List<Booking> priorBookings = allMemberBookings.stream()
                .filter(b -> currentBookingId == null || !Objects.equals(b.getId(), currentBookingId))
                .filter(b -> b.getStatus() != BookingStatus.DRAFT)
                .toList();

        int priorBookingsTotal = priorBookings.size();

        int priorBookingsCompleted = (int) priorBookings.stream()
                .filter(b -> b.getStatus() == BookingStatus.COMPLETED)
                .count();

        int priorBookingsCancelled = (int) priorBookings.stream()
                .filter(b -> b.getStatus() == BookingStatus.CANCELLED)
                .count();

        int priorBookingsRejected = (int) priorBookings.stream()
                .filter(b -> b.getStatus() == BookingStatus.REJECTED)
                .count();

        int priorBookingsPending = (int) priorBookings.stream()
                .filter(b -> b.getStatus() == BookingStatus.PENDING_APPROVAL || b.getStatus() == BookingStatus.PENDING)
                .count();

        // 2. Resolve external telemetry mapping for target resource
        Optional<ResourceTelemetryMapping> targetMappingOpt =
                mappingRepository.findByResourceId(targetResourceId);
        String externalResourceId = targetMappingOpt.map(ResourceTelemetryMapping::getExternalResourceId).orElse(null);

        // 3. Current Resource Health: describes present operating snapshot only (never historical evidence or future guarantee)
        ExternalResourceHealth currentHealth = null;
        if (externalResourceId != null) {
            currentHealth = telemetryClient.getCurrentHealth(externalResourceId).orElse(null);
        }

        // 4. Historical Telemetry: Query exact intervals ONLY for eligible past COMPLETED reservations on target GPU
        Double historicalAvgGpuUtilPct = null;
        Double historicalPeakGpuUtilPct = null;
        String historicalTelemetryQuality = "NO_PRIOR_USAGE";
        boolean hasHistoricalTelemetry = false;

        List<PastBookingTelemetryRecord> pastBookingRecords = new ArrayList<>();

        if (externalResourceId != null) {
            List<Booking> completedPastBookingsOnTarget = priorBookings.stream()
                    .filter(b -> b.getStatus() == BookingStatus.COMPLETED)
                    .filter(b -> b.getResource() != null && Objects.equals(b.getResource().getId(), targetResourceId))
                    .toList();

            if (!completedPastBookingsOnTarget.isEmpty()) {
                List<ExternalTelemetryMeasurement> aggregatedMeasurements = new ArrayList<>();
                boolean anyComplete = false;
                boolean anyPartial = false;

                for (Booking pastBooking : completedPastBookingsOnTarget) {
                    if (pastBooking.getStartTime() == null || pastBooking.getEndTime() == null) {
                        continue;
                    }

                    Instant start = pastBooking.getStartTime().toInstant(ZoneOffset.UTC);
                    Instant end = pastBooking.getEndTime().toInstant(ZoneOffset.UTC);

                    Optional<ExternalHistoricalTelemetry> histOpt =
                            telemetryClient.getHistoricalTelemetry(externalResourceId, start, end);

                    if (histOpt.isPresent()) {
                        ExternalHistoricalTelemetry hist = histOpt.get();
                        String quality = hist.dataQuality() != null ? hist.dataQuality() : "UNAVAILABLE";
                        if ("COMPLETE".equalsIgnoreCase(quality)) {
                            anyComplete = true;
                        } else if ("PARTIAL".equalsIgnoreCase(quality)) {
                            anyPartial = true;
                        }

                        List<ExternalTelemetryMeasurement> rawMeasurements = hist.measurements() != null ? hist.measurements() : List.of();
                        if (!rawMeasurements.isEmpty()) {
                            aggregatedMeasurements.addAll(rawMeasurements);
                        }

                        // Per-booking statistics & selection
                        Double bookingAvgGpu = null;
                        Double bookingPeakGpu = null;
                        Double bookingAvgMem = null;
                        Double bookingAvgTemp = null;
                        Double bookingAvgPower = null;

                        if (!rawMeasurements.isEmpty()) {
                            double sumGpu = rawMeasurements.stream().mapToDouble(ExternalTelemetryMeasurement::gpuUtilizationPct).sum();
                            double peakGpu = rawMeasurements.stream().mapToDouble(ExternalTelemetryMeasurement::gpuUtilizationPct).max().orElse(0.0);
                            double sumMem = rawMeasurements.stream().mapToDouble(ExternalTelemetryMeasurement::gpuMemoryUtilizationPct).sum();
                            double sumTemp = rawMeasurements.stream().mapToDouble(ExternalTelemetryMeasurement::temperatureCelsius).sum();
                            double sumPower = rawMeasurements.stream().mapToDouble(ExternalTelemetryMeasurement::powerDrawWatts).sum();

                            int count = rawMeasurements.size();
                            bookingAvgGpu = Math.round((sumGpu / count) * 10.0) / 10.0;
                            bookingPeakGpu = Math.round(peakGpu * 10.0) / 10.0;
                            bookingAvgMem = Math.round((sumMem / count) * 10.0) / 10.0;
                            bookingAvgTemp = Math.round((sumTemp / count) * 10.0) / 10.0;
                            bookingAvgPower = Math.round((sumPower / count) * 10.0) / 10.0;
                        }

                        boolean hasMissing = hist.coveragePct() < 100.0 || !"COMPLETE".equalsIgnoreCase(quality);
                        String missingReason = hasMissing ? "Telemetry coverage is " + hist.coveragePct() + "% (" + quality + ")" : null;

                        List<ExternalTelemetryMeasurement> selectedMeasurements = selectKeyMeasurements(rawMeasurements, 15);

                        pastBookingRecords.add(new PastBookingTelemetryRecord(
                                pastBooking.getId(),
                                pastBooking.getStartTime(),
                                pastBooking.getEndTime(),
                                quality,
                                hist.coveragePct(),
                                hasMissing,
                                missingReason,
                                bookingAvgGpu,
                                bookingPeakGpu,
                                bookingAvgMem,
                                bookingAvgTemp,
                                bookingAvgPower,
                                selectedMeasurements,
                                rawMeasurements.size(),
                                hist.isDemonstrationData()
                        ));
                    }
                }

                if (!aggregatedMeasurements.isEmpty()) {
                    hasHistoricalTelemetry = true;
                    double sumUtil = aggregatedMeasurements.stream()
                            .mapToDouble(ExternalTelemetryMeasurement::gpuUtilizationPct)
                            .sum();
                    double maxUtil = aggregatedMeasurements.stream()
                            .mapToDouble(ExternalTelemetryMeasurement::gpuUtilizationPct)
                            .max()
                            .orElse(0.0);

                    historicalAvgGpuUtilPct = Math.round((sumUtil / aggregatedMeasurements.size()) * 10.0) / 10.0;
                    historicalPeakGpuUtilPct = Math.round(maxUtil * 10.0) / 10.0;
                    historicalTelemetryQuality = anyComplete ? "COMPLETE" : (anyPartial ? "PARTIAL" : "UNAVAILABLE");
                } else {
                    historicalTelemetryQuality = "UNAVAILABLE";
                }
            }
        }

        return new RequesterHistoryProfile(
                memberId,
                targetResourceId,
                externalResourceId,
                priorBookingsTotal,
                priorBookingsCompleted,
                priorBookingsCancelled,
                priorBookingsRejected,
                priorBookingsPending,
                historicalAvgGpuUtilPct,
                historicalPeakGpuUtilPct,
                historicalTelemetryQuality,
                currentHealth,
                hasHistoricalTelemetry,
                DATA_LIMITATIONS_DISCLOSURE,
                pastBookingRecords
        );
    }

    /**
     * Deterministically selects up to maxKeep measurements, preserving boundary points (start/end),
     * peak GPU utilization, minimum GPU utilization, peak memory utilization, and uniform intermediate intervals.
     */
    public static List<ExternalTelemetryMeasurement> selectKeyMeasurements(List<ExternalTelemetryMeasurement> all, int maxKeep) {
        if (all == null || all.size() <= maxKeep) {
            return all != null ? new ArrayList<>(all) : List.of();
        }

        java.util.Set<Integer> selectedIndices = new java.util.TreeSet<>();
        selectedIndices.add(0);
        selectedIndices.add(all.size() - 1);

        int peakGpuIdx = 0;
        int minGpuIdx = 0;
        int peakMemIdx = 0;
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).gpuUtilizationPct() > all.get(peakGpuIdx).gpuUtilizationPct()) {
                peakGpuIdx = i;
            }
            if (all.get(i).gpuUtilizationPct() < all.get(minGpuIdx).gpuUtilizationPct()) {
                minGpuIdx = i;
            }
            if (all.get(i).gpuMemoryUtilizationPct() > all.get(peakMemIdx).gpuMemoryUtilizationPct()) {
                peakMemIdx = i;
            }
        }
        selectedIndices.add(peakGpuIdx);
        selectedIndices.add(minGpuIdx);
        selectedIndices.add(peakMemIdx);

        double step = (double) (all.size() - 1) / (maxKeep - 1);
        for (int k = 1; k < maxKeep - 1; k++) {
            selectedIndices.add((int) Math.round(k * step));
        }

        List<ExternalTelemetryMeasurement> result = new ArrayList<>();
        for (int idx : selectedIndices) {
            result.add(all.get(idx));
        }
        return result;
    }
}
