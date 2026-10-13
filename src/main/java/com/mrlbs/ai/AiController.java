package com.mrlbs.ai;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.Map;

@RestController
@RequestMapping("/api/ai")
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
                "model", geminiClient.getModelName()
        ));
    }

    /**
     * BOOK-109: Parses a natural-language booking query into structured search criteria.
     */
    @PostMapping("/parse-intent")
    public ResponseEntity<?> parseIntent(
            @Valid @RequestBody AiPromptRequest request,
            @RequestParam(required = false) String contextDate,
            java.security.Principal principal,
            jakarta.servlet.http.HttpServletRequest httpRequest) {
        String clientKey = resolveClientKey(principal, httpRequest);
        if (!rateLimiter.tryAcquire(clientKey)) {
            return ResponseEntity.status(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS)
                    .body(Map.of("error", "Rate limit exceeded. Please wait a moment before sending more AI requests."));
        }

        LocalDate refDate = (contextDate != null && !contextDate.isBlank())
                ? LocalDate.parse(contextDate)
                : LocalDate.now();
        return ResponseEntity.ok(recommendationService.parseIntent(request.prompt(), refDate));
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
        String clientKey = resolveClientKey(principal, httpRequest);
        if (!rateLimiter.tryAcquire(clientKey)) {
            return ResponseEntity.status(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS)
                    .body(AiRecommendationResponse.error("Rate limit exceeded. Please wait a moment before sending more AI requests."));
        }

        AiRecommendationResponse response = recommendationService.getRecommendations(request);
        return ResponseEntity.ok(response);
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
