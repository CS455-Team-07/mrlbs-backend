package com.mrlbs.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
public class GeminiClient {

    private static final Logger log = LoggerFactory.getLogger(GeminiClient.class);

    private final GeminiConfig config;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final com.mrlbs.ai.observability.AiAgentLogger aiLogger;

    public GeminiClient(GeminiConfig config, ObjectMapper objectMapper) {
        this(config, objectMapper, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public GeminiClient(GeminiConfig config, ObjectMapper objectMapper,
                        @org.springframework.beans.factory.annotation.Autowired(required = false) com.mrlbs.ai.observability.AiAgentLogger aiLogger) {
        this.config = config;
        this.objectMapper = objectMapper;
        this.aiLogger = aiLogger;

        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(config.getTimeoutSeconds()));
        requestFactory.setReadTimeout(Duration.ofSeconds(config.getTimeoutSeconds()));

        this.restClient = RestClient.builder()
                .requestFactory(requestFactory)
                .build();
    }

    public boolean isConfigured() {
        return config.isConfigured();
    }

    public String getModelName() {
        return config.getModel();
    }

    /**
     * Sends a prompt to the Gemini API requesting structured JSON output.
     *
     * @param systemInstruction Background context or system instructions.
     * @param userPrompt        The prompt content to evaluate.
     * @return Optional containing the raw JSON response text from Gemini, or empty on failure.
     */
    public Optional<String> generateStructuredContent(String systemInstruction, String userPrompt) {
        String correlationId = org.slf4j.MDC.get(com.mrlbs.ai.observability.AiAgentLogger.MDC_CORRELATION_ID);
        String agent = org.slf4j.MDC.get(com.mrlbs.ai.observability.AiAgentLogger.MDC_AGENT);
        if (agent == null) {
            agent = "AI_CLIENT";
        }

        if (!config.isConfigured()) {
            log.warn("Gemini API call skipped: GEMINI_API_KEY is not configured.");
            if (aiLogger != null) {
                aiLogger.logModelFailure(agent, correlationId, config.getModel(), 0, "NOT_CONFIGURED", "GEMINI_API_KEY is not configured", true);
            }
            return Optional.empty();
        }

        String url = String.format("%s/models/%s:generateContent",
                config.getBaseUrl(), config.getModel());

        Map<String, Object> requestPayload = Map.of(
                "contents", List.of(
                        Map.of(
                                "role", "user",
                                "parts", List.of(
                                        Map.of("text", userPrompt)
                                )
                        )
                ),
                "systemInstruction", Map.of(
                        "parts", List.of(
                                Map.of("text", systemInstruction)
                        )
                ),
                "generationConfig", Map.of(
                        "temperature", 0.1,
                        "responseMimeType", "application/json",
                        "maxOutputTokens", 512
                )
        );

        long startNs = System.nanoTime();
        if (aiLogger != null) {
            aiLogger.logToolStart(agent, correlationId, com.mrlbs.ai.observability.AiAgentLogger.TOOL_GEMINI,
                    Map.of("model", config.getModel(), "promptChars", userPrompt != null ? userPrompt.length() : 0));
        }

        try {
            String responseBody = restClient.post()
                    .uri(url)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .header("x-goog-api-key", config.getApiKey())
                    .body(requestPayload)
                    .retrieve()
                    .body(String.class);

            long durationMs = (System.nanoTime() - startNs) / 1_000_000;

            if (responseBody == null || responseBody.isBlank()) {
                log.warn("Gemini API returned an empty response body.");
                if (aiLogger != null) {
                    aiLogger.logValidationFailure(agent, correlationId, "EMPTY_RESPONSE", "");
                    aiLogger.logToolFailure(agent, correlationId, com.mrlbs.ai.observability.AiAgentLogger.TOOL_GEMINI,
                            durationMs, "EMPTY_RESPONSE", "Gemini returned empty response body");
                }
                return Optional.empty();
            }

            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode textNode = root.at("/candidates/0/content/parts/0/text");
            if (textNode.isMissingNode() || textNode.asText().isBlank()) {
                String sanitizedBody = com.mrlbs.ai.observability.AiLogSanitizer.truncate(responseBody, 150);
                log.warn("Gemini response did not contain candidates[0].content.parts[0].text: {}", sanitizedBody);
                if (aiLogger != null) {
                    aiLogger.logValidationFailure(agent, correlationId, "MISSING_CANDIDATE_TEXT", sanitizedBody);
                    aiLogger.logToolFailure(agent, correlationId, com.mrlbs.ai.observability.AiAgentLogger.TOOL_GEMINI,
                            durationMs, "SCHEMA_VALIDATION_FAILED", "Response did not contain candidates[0].content.parts[0].text");
                }
                return Optional.empty();
            }

            String contentText = textNode.asText();
            if (aiLogger != null) {
                aiLogger.logModelCall(agent, correlationId, config.getModel(), durationMs, "SUCCESS",
                        userPrompt != null ? userPrompt.length() : 0, contentText.length());
                aiLogger.logToolComplete(agent, correlationId, com.mrlbs.ai.observability.AiAgentLogger.TOOL_GEMINI,
                        durationMs, "SUCCESS", Map.of("responseChars", contentText.length()));
            }

            return Optional.of(contentText);
        } catch (RestClientResponseException e) {
            long durationMs = (System.nanoTime() - startNs) / 1_000_000;
            String sanitizedErr = com.mrlbs.ai.observability.AiLogSanitizer.sanitize(e.getResponseBodyAsString());
            log.error("Gemini API returned HTTP status {}: {}", e.getStatusCode(), sanitizedErr);
            if (aiLogger != null) {
                aiLogger.logModelFailure(agent, correlationId, config.getModel(), durationMs,
                        "HTTP_" + e.getStatusCode(), sanitizedErr, true);
                aiLogger.logToolFailure(agent, correlationId, com.mrlbs.ai.observability.AiAgentLogger.TOOL_GEMINI,
                        durationMs, "HTTP_" + e.getStatusCode(), sanitizedErr);
            }
            return Optional.empty();
        } catch (Exception e) {
            long durationMs = (System.nanoTime() - startNs) / 1_000_000;
            String sanitizedErr = com.mrlbs.ai.observability.AiLogSanitizer.sanitize(e.getMessage());
            log.error("Error communicating with Gemini API: {}", sanitizedErr);
            if (aiLogger != null) {
                aiLogger.logModelFailure(agent, correlationId, config.getModel(), durationMs,
                        e.getClass().getSimpleName(), sanitizedErr, true);
                aiLogger.logToolFailure(agent, correlationId, com.mrlbs.ai.observability.AiAgentLogger.TOOL_GEMINI,
                        durationMs, e.getClass().getSimpleName(), sanitizedErr);
            }
            return Optional.empty();
        }
    }
}
