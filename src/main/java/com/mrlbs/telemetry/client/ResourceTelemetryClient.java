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
    private final com.mrlbs.ai.observability.AiAgentLogger aiLogger;

    @Autowired
    public ResourceTelemetryClient(
            @Value("${mrlbs.telemetry.service-url:http://localhost:8085}") String baseUrl,
            @Autowired(required = false) com.mrlbs.ai.observability.AiAgentLogger aiLogger
    ) {
        this.baseUrl = baseUrl;
        this.aiLogger = aiLogger;
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .build();
    }

    public ResourceTelemetryClient(
            @Value("${mrlbs.telemetry.service-url:http://localhost:8085}") String baseUrl
    ) {
        this(baseUrl, (com.mrlbs.ai.observability.AiAgentLogger) null);
    }

    public ResourceTelemetryClient(RestClient.Builder builder, String baseUrl) {
        this.baseUrl = baseUrl;
        this.aiLogger = null;
        this.restClient = builder
                .baseUrl(baseUrl)
                .build();
    }

    public ResourceTelemetryClient(RestClient restClient, String baseUrl) {
        this(restClient, baseUrl, null);
    }

    public ResourceTelemetryClient(RestClient restClient, String baseUrl, com.mrlbs.ai.observability.AiAgentLogger aiLogger) {
        this.restClient = restClient;
        this.baseUrl = baseUrl;
        this.aiLogger = aiLogger;
    }

    public Optional<ExternalResourceHealth> getCurrentHealth(String externalResourceId) {
        if (externalResourceId == null || externalResourceId.isBlank()) {
            return Optional.empty();
        }

        String correlationId = org.slf4j.MDC.get(com.mrlbs.ai.observability.AiAgentLogger.MDC_CORRELATION_ID);
        String agent = org.slf4j.MDC.get(com.mrlbs.ai.observability.AiAgentLogger.MDC_AGENT);
        if (agent == null) {
            agent = com.mrlbs.ai.observability.AiAgentLogger.AGENT_REVIEW;
        }

        long startNs = System.nanoTime();
        if (aiLogger != null) {
            aiLogger.logToolStart(agent, correlationId, com.mrlbs.ai.observability.AiAgentLogger.TOOL_TELEMETRY_HEALTH,
                    java.util.Map.of("resourceId", externalResourceId));
        }

        try {
            ExternalResourceHealth health = restClient.get()
                    .uri("/api/telemetry/resources/{id}/health", externalResourceId)
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, (request, response) -> {
                        log.debug("Telemetry health client error {}: resource {}", response.getStatusCode(), externalResourceId);
                    })
                    .body(ExternalResourceHealth.class);

            long durationMs = (System.nanoTime() - startNs) / 1_000_000;
            if (aiLogger != null) {
                if (health != null) {
                    aiLogger.logToolComplete(agent, correlationId, com.mrlbs.ai.observability.AiAgentLogger.TOOL_TELEMETRY_HEALTH,
                            durationMs, "SUCCESS", java.util.Map.of(
                                    "status", health.healthStatus() != null ? health.healthStatus() : "UNKNOWN",
                                    "gpuUtil", health.gpuUtilizationPct(),
                                    "isDemo", health.isDemonstrationData()
                            ));
                } else {
                    aiLogger.logToolComplete(agent, correlationId, com.mrlbs.ai.observability.AiAgentLogger.TOOL_TELEMETRY_HEALTH,
                            durationMs, "EMPTY_BODY", java.util.Map.of("resourceId", externalResourceId));
                }
            }

            return Optional.ofNullable(health);
        } catch (HttpClientErrorException.NotFound e) {
            long durationMs = (System.nanoTime() - startNs) / 1_000_000;
            log.debug("Telemetry health not found (404) for resource {}", externalResourceId);
            if (aiLogger != null) {
                aiLogger.logToolComplete(agent, correlationId, com.mrlbs.ai.observability.AiAgentLogger.TOOL_TELEMETRY_HEALTH,
                        durationMs, "NOT_FOUND", java.util.Map.of("resourceId", externalResourceId));
            }
            return Optional.empty();
        } catch (Exception e) {
            long durationMs = (System.nanoTime() - startNs) / 1_000_000;
            String sanitizedMsg = com.mrlbs.ai.observability.AiLogSanitizer.sanitize(e.getMessage());
            log.warn("Telemetry service unavailable or error querying health for resource {}: {}",
                    externalResourceId, sanitizedMsg);
            if (aiLogger != null) {
                aiLogger.logToolFailure(agent, correlationId, com.mrlbs.ai.observability.AiAgentLogger.TOOL_TELEMETRY_HEALTH,
                        durationMs, e.getClass().getSimpleName(), sanitizedMsg);
            }
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

        String correlationId = org.slf4j.MDC.get(com.mrlbs.ai.observability.AiAgentLogger.MDC_CORRELATION_ID);
        String agent = org.slf4j.MDC.get(com.mrlbs.ai.observability.AiAgentLogger.MDC_AGENT);
        if (agent == null) {
            agent = com.mrlbs.ai.observability.AiAgentLogger.AGENT_REVIEW;
        }

        long startNs = System.nanoTime();
        if (aiLogger != null) {
            aiLogger.logToolStart(agent, correlationId, com.mrlbs.ai.observability.AiAgentLogger.TOOL_TELEMETRY_HISTORY,
                    java.util.Map.of("resourceId", externalResourceId, "startTime", startTime.toString(), "endTime", endTime.toString()));
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

            long durationMs = (System.nanoTime() - startNs) / 1_000_000;
            if (aiLogger != null) {
                if (history != null) {
                    aiLogger.logToolComplete(agent, correlationId, com.mrlbs.ai.observability.AiAgentLogger.TOOL_TELEMETRY_HISTORY,
                            durationMs, "SUCCESS", java.util.Map.of(
                                    "quality", history.dataQuality() != null ? history.dataQuality() : "UNKNOWN",
                                    "measurementsCount", history.measurements() != null ? history.measurements().size() : 0,
                                    "isDemo", history.isDemonstrationData()
                            ));
                } else {
                    aiLogger.logToolComplete(agent, correlationId, com.mrlbs.ai.observability.AiAgentLogger.TOOL_TELEMETRY_HISTORY,
                            durationMs, "EMPTY_BODY", java.util.Map.of("resourceId", externalResourceId));
                }
            }

            return Optional.ofNullable(history);
        } catch (HttpClientErrorException.NotFound e) {
            long durationMs = (System.nanoTime() - startNs) / 1_000_000;
            log.debug("Telemetry history not found (404) for resource {}", externalResourceId);
            if (aiLogger != null) {
                aiLogger.logToolComplete(agent, correlationId, com.mrlbs.ai.observability.AiAgentLogger.TOOL_TELEMETRY_HISTORY,
                        durationMs, "NOT_FOUND", java.util.Map.of("resourceId", externalResourceId));
            }
            return Optional.empty();
        } catch (Exception e) {
            long durationMs = (System.nanoTime() - startNs) / 1_000_000;
            String sanitizedMsg = com.mrlbs.ai.observability.AiLogSanitizer.sanitize(e.getMessage());
            log.warn("Telemetry service unavailable or error querying history for resource {}: {}",
                    externalResourceId, sanitizedMsg);
            if (aiLogger != null) {
                aiLogger.logToolFailure(agent, correlationId, com.mrlbs.ai.observability.AiAgentLogger.TOOL_TELEMETRY_HISTORY,
                        durationMs, e.getClass().getSimpleName(), sanitizedMsg);
            }
            return Optional.empty();
        }
    }

    public String getBaseUrl() {
        return baseUrl;
    }
}
