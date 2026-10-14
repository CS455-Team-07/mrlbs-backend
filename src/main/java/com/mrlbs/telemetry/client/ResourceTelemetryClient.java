package com.mrlbs.telemetry.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.Optional;

@Component
public class ResourceTelemetryClient {

    private static final Logger log = LoggerFactory.getLogger(ResourceTelemetryClient.class);

    private final RestClient restClient;
    private final String baseUrl;

    @Autowired
    public ResourceTelemetryClient(
            @Value("${mrlbs.telemetry.service-url:http://localhost:8085}") String baseUrl
    ) {
        this.baseUrl = baseUrl;
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .build();
    }

    public ResourceTelemetryClient(RestClient.Builder builder, String baseUrl) {
        this.baseUrl = baseUrl;
        this.restClient = builder
                .baseUrl(baseUrl)
                .build();
    }

    public ResourceTelemetryClient(RestClient restClient, String baseUrl) {
        this.restClient = restClient;
        this.baseUrl = baseUrl;
    }

    public Optional<ExternalResourceHealth> getCurrentHealth(String externalResourceId) {
        if (externalResourceId == null || externalResourceId.isBlank()) {
            return Optional.empty();
        }

        try {
            ExternalResourceHealth health = restClient.get()
                    .uri("/api/telemetry/resources/{id}/health", externalResourceId)
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, (request, response) -> {
                        log.debug("Telemetry health client error {}: resource {}", response.getStatusCode(), externalResourceId);
                    })
                    .body(ExternalResourceHealth.class);

            return Optional.ofNullable(health);
        } catch (HttpClientErrorException.NotFound e) {
            log.debug("Telemetry health not found (404) for resource {}", externalResourceId);
            return Optional.empty();
        } catch (Exception e) {
            log.warn("Telemetry service unavailable or error querying health for resource {}: {}",
                    externalResourceId, e.getMessage());
            return Optional.empty();
        }
    }

    public Optional<ExternalHistoricalTelemetry> getHistoricalTelemetry(
            String externalResourceId,
            Instant startTime,
            Instant endTime
    ) {
        if (externalResourceId == null || externalResourceId.isBlank() || startTime == null || endTime == null) {
            return Optional.empty();
        }

        try {
            ExternalHistoricalTelemetry history = restClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/api/telemetry/resources/{id}/history")
                            .queryParam("startTime", startTime.toString())
                            .queryParam("endTime", endTime.toString())
                            .build(externalResourceId))
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, (request, response) -> {
                        log.debug("Telemetry history client error {}: resource {}", response.getStatusCode(), externalResourceId);
                    })
                    .body(ExternalHistoricalTelemetry.class);

            return Optional.ofNullable(history);
        } catch (HttpClientErrorException.NotFound e) {
            log.debug("Telemetry history not found (404) for resource {}", externalResourceId);
            return Optional.empty();
        } catch (Exception e) {
            log.warn("Telemetry service unavailable or error querying history for resource {}: {}",
                    externalResourceId, e.getMessage());
            return Optional.empty();
        }
    }

    public String getBaseUrl() {
        return baseUrl;
    }
}
