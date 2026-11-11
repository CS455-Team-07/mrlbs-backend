package com.mrlbs.ai;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;

import java.time.LocalDate;
import java.util.Map;

@RestController
@RequestMapping("/api/ai")
@Validated
public class AiController {

    private final AiRecommendationService recommendationService;
    private final GeminiClient geminiClient;
    private final AiRateLimiter rateLimiter;

    public AiController(AiRecommendationService recommendationService,
                        GeminiClient geminiClient,
                        AiRateLimiter rateLimiter) {
        this.recommendationService = recommendationService;
        this.geminiClient = geminiClient;
        this.rateLimiter = rateLimiter;
    }

    /**
     * Checks the configuration status of the AI Assistant.
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus() {
        return ResponseEntity.ok(Map.of(
                "enabled", true,
                "geminiConfigured", geminiClient.isConfigured(),
                "model", geminiClient.isConfigured() ? geminiClient.getModelName() : "local-deterministic-engine"
        ));
    }

    /**
     * BOOK-109: Parses a natural-language booking query into structured search criteria.
     */
    @PostMapping("/parse-intent")
    public ResponseEntity<?> parseIntent(
            @Valid @RequestBody AiPromptRequest request,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate contextDate,
            java.security.Principal principal,
            jakarta.servlet.http.HttpServletRequest httpRequest) {
        String correlationId = resolveCorrelationId(httpRequest);
        org.slf4j.MDC.put(com.mrlbs.ai.observability.AiAgentLogger.MDC_CORRELATION_ID, correlationId);
        org.slf4j.MDC.put(com.mrlbs.ai.observability.AiAgentLogger.MDC_AGENT, com.mrlbs.ai.observability.AiAgentLogger.AGENT_RECOMMENDATION);

        try {
            String clientKey = resolveClientKey(principal, httpRequest);
            if (!rateLimiter.tryAcquire(clientKey)) {
                return ResponseEntity.status(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS)
                        .header("X-Correlation-ID", correlationId)
                        .body(Map.of("error", "Rate limit exceeded. Please wait a moment before sending more AI requests."));
            }

            LocalDate refDate = contextDate == null ? LocalDate.now() : contextDate;
            return ResponseEntity.ok()
                    .header("X-Correlation-ID", correlationId)
                    .body(recommendationService.parseIntent(request.prompt(), refDate));
        } finally {
            org.slf4j.MDC.clear();
        }
    }

    /**
     * BOOK-110 & BOOK-111: Natural-language recommendation engine.
     * Evaluates real database resources and returns 1 top recommendation + 3-4 alternatives.
     */
    @PostMapping("/recommend")
    public ResponseEntity<AiRecommendationResponse> getRecommendations(
            @Valid @RequestBody AiPromptRequest request,
            java.security.Principal principal,
            jakarta.servlet.http.HttpServletRequest httpRequest) {
        String correlationId = resolveCorrelationId(httpRequest);
        org.slf4j.MDC.put(com.mrlbs.ai.observability.AiAgentLogger.MDC_CORRELATION_ID, correlationId);
        org.slf4j.MDC.put(com.mrlbs.ai.observability.AiAgentLogger.MDC_AGENT, com.mrlbs.ai.observability.AiAgentLogger.AGENT_RECOMMENDATION);

        try {
            String clientKey = resolveClientKey(principal, httpRequest);
            if (!rateLimiter.tryAcquire(clientKey)) {
                return ResponseEntity.status(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS)
                        .header("X-Correlation-ID", correlationId)
                        .body(AiRecommendationResponse.error("Rate limit exceeded. Please wait a moment before sending more AI requests."));
            }

            AiRecommendationResponse response = recommendationService.getRecommendations(request);
            return ResponseEntity.ok()
                    .header("X-Correlation-ID", correlationId)
                    .body(response);
        } finally {
            org.slf4j.MDC.clear();
        }
    }

    private String resolveCorrelationId(jakarta.servlet.http.HttpServletRequest httpRequest) {
        if (httpRequest != null) {
            String header = httpRequest.getHeader("X-Correlation-ID");
            if (header != null && !header.isBlank()) {
                return header.trim();
            }
            header = httpRequest.getHeader("X-Request-ID");
            if (header != null && !header.isBlank()) {
                return header.trim();
            }
        }
        return java.util.UUID.randomUUID().toString();
    }

    private String resolveClientKey(java.security.Principal principal, jakarta.servlet.http.HttpServletRequest httpRequest) {
        if (principal != null && principal.getName() != null && !principal.getName().isBlank()) {
            return principal.getName();
        }
        if (httpRequest != null) {
            String forwarded = httpRequest.getHeader("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) {
                return forwarded.split(",")[0].trim();
            }
            return httpRequest.getRemoteAddr();
        }
        return "anonymous";
    }
}
