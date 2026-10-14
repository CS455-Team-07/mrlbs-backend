package com.mrlbs.telemetry.client;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class ResourceTelemetryClientTest {

    private ResourceTelemetryClient telemetryClient;
    private MockRestServiceServer mockServer;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        mockServer = MockRestServiceServer.bindTo(builder).build();
        telemetryClient = new ResourceTelemetryClient(builder, "http://localhost:8085");
    }

    @Test
    void testGetCurrentHealth_Success() {
        String json = """
            {
                "externalResourceId": "gpu-node-01",
                "healthStatus": "HEALTHY",
                "gpuUtilizationPct": 42.5,
                "gpuMemoryUtilizationPct": 65.0,
                "temperatureCelsius": 68.0,
                "powerDrawWatts": 240.0,
                "timestamp": "2026-10-14T16:50:00Z",
                "dataSource": "DEMO_DETERMINISTIC_FIXTURE",
                "isDemonstrationData": true
            }
            """;

        mockServer.expect(requestTo("http://localhost:8085/api/telemetry/resources/gpu-node-01/health"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));

        Optional<ExternalResourceHealth> healthOpt = telemetryClient.getCurrentHealth("gpu-node-01");
        mockServer.verify();

        assertTrue(healthOpt.isPresent());
        ExternalResourceHealth health = healthOpt.get();
        assertEquals("gpu-node-01", health.externalResourceId());
        assertEquals("HEALTHY", health.healthStatus());
        assertEquals(42.5, health.gpuUtilizationPct());
        assertTrue(health.isDemonstrationData());
    }

    @Test
    void testGetCurrentHealth_NotFoundReturnsEmpty() {
        mockServer.expect(requestTo("http://localhost:8085/api/telemetry/resources/unknown-gpu/health"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withResourceNotFound());

        Optional<ExternalResourceHealth> healthOpt = telemetryClient.getCurrentHealth("unknown-gpu");
        mockServer.verify();

        assertTrue(healthOpt.isEmpty());
    }

    @Test
    void testGetCurrentHealth_ServerErrorReturnsEmptyWithoutThrowing() {
        mockServer.expect(requestTo("http://localhost:8085/api/telemetry/resources/gpu-node-01/health"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withServerError());

        Optional<ExternalResourceHealth> healthOpt = telemetryClient.getCurrentHealth("gpu-node-01");
        mockServer.verify();

        assertTrue(healthOpt.isEmpty());
    }

    @Test
    void testGetHistoricalTelemetry_Success() {
        Instant start = Instant.parse("2026-10-10T10:00:00Z");
        Instant end = Instant.parse("2026-10-10T12:00:00Z");

        String json = """
            {
                "externalResourceId": "gpu-node-01",
                "queryStartTime": "2026-10-10T10:00:00Z",
                "queryEndTime": "2026-10-10T12:00:00Z",
                "dataQuality": "COMPLETE",
                "coveragePct": 100.0,
                "isDemonstrationData": true,
                "dataSource": "DEMO_DETERMINISTIC_FIXTURE",
                "measurements": [
                    {
                        "timestamp": "2026-10-10T10:15:00Z",
                        "gpuUtilizationPct": 82.0,
                        "gpuMemoryUtilizationPct": 75.0,
                        "temperatureCelsius": 70.0,
                        "powerDrawWatts": 275.0
                    }
                ]
            }
            """;

        mockServer.expect(requestTo("http://localhost:8085/api/telemetry/resources/gpu-node-01/history?startTime=2026-10-10T10:00:00Z&endTime=2026-10-10T12:00:00Z"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));

        Optional<ExternalHistoricalTelemetry> historyOpt =
                telemetryClient.getHistoricalTelemetry("gpu-node-01", start, end);
        mockServer.verify();

        assertTrue(historyOpt.isPresent());
        ExternalHistoricalTelemetry history = historyOpt.get();
        assertEquals("COMPLETE", history.dataQuality());
        assertEquals(100.0, history.coveragePct());
        assertEquals(1, history.measurements().size());
    }

    @Test
    void testGetHistoricalTelemetry_NotFoundReturnsEmpty() {
        Instant start = Instant.parse("2026-10-10T10:00:00Z");
        Instant end = Instant.parse("2026-10-10T12:00:00Z");

        mockServer.expect(requestTo("http://localhost:8085/api/telemetry/resources/ghost-gpu/history?startTime=2026-10-10T10:00:00Z&endTime=2026-10-10T12:00:00Z"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        Optional<ExternalHistoricalTelemetry> historyOpt =
                telemetryClient.getHistoricalTelemetry("ghost-gpu", start, end);
        mockServer.verify();

        assertTrue(historyOpt.isEmpty());
    }
}
