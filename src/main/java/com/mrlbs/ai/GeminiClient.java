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

    public GeminiClient(GeminiConfig config, ObjectMapper objectMapper) {
        this.config = config;
        this.objectMapper = objectMapper;

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
        if (!config.isConfigured()) {
            log.warn("Gemini API call skipped: GEMINI_API_KEY is not configured.");
            return Optional.empty();
        }

        String url = String.format("%s/models/%s:generateContent?key=%s",
                config.getBaseUrl(), config.getModel(), config.getApiKey());

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

        try {
            String responseBody = restClient.post()
                    .uri(url)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .header("x-goog-api-key", config.getApiKey())
                    .body(requestPayload)
                    .retrieve()
                    .body(String.class);

            if (responseBody == null || responseBody.isBlank()) {
                log.warn("Gemini API returned an empty response body.");
                return Optional.empty();
            }

            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode textNode = root.at("/candidates/0/content/parts/0/text");
            if (textNode.isMissingNode() || textNode.asText().isBlank()) {
                log.warn("Gemini response did not contain candidates[0].content.parts[0].text: {}", responseBody);
                return Optional.empty();
            }

            return Optional.of(textNode.asText());
        } catch (RestClientResponseException e) {
            log.error("Gemini API returned HTTP status {}: {}", e.getStatusCode(), e.getResponseBodyAsString());
            return Optional.empty();
        } catch (Exception e) {
            log.error("Error communicating with Gemini API: {}", e.getMessage());
            return Optional.empty();
        }
    }
}
