package com.mrlbs.ai.observability;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.Map;
import java.util.StringJoiner;

/**
 * Centralized observability and diagnostic logger for AI agents (NFR-8.2 / US-10).
 * Provides structured, traceable diagnostic logging for agent lifecycles, tool calls,
 * external API integrations, fallback decisions, and model responses while strictly
 * enforcing redaction of sensitive credentials.
 */
@Component
public class AiAgentLogger {

    private static final Logger log = LoggerFactory.getLogger("com.mrlbs.ai.observability");

    public static final String AGENT_REVIEW = "AI_REQUEST_REVIEW_AGENT";
    public static final String AGENT_RECOMMENDATION = "AI_RESOURCE_RECOMMENDATION_AGENT";

    public static final String TOOL_GEMINI = "gemini-generate-content";
    public static final String TOOL_TELEMETRY_HEALTH = "telemetry-current-health";
    public static final String TOOL_TELEMETRY_HISTORY = "telemetry-historical";
    public static final String TOOL_REQUESTER_HISTORY = "requester-history-analyzer";
    public static final String TOOL_CATALOG_SEARCH = "resource-catalog-lookup";
    public static final String TOOL_INTENT_PARSER = "gemini-intent-parser";
    public static final String TOOL_PERSISTENCE = "review-repository-save";

    public static final String MDC_CORRELATION_ID = "correlationId";
    public static final String MDC_AGENT = "agent";
    public static final String MDC_BOOKING_ID = "bookingId";

    /**
     * Logs the start of an AI agent invocation.
     */
    public void logAgentStart(String agent, String correlationId, String model, Map<String, Object> inputMetadata) {
        try {
            String metaStr = formatMetadata(inputMetadata);
            log.info("[AI_OBSERVABILITY] event=AGENT_START [AI:START] agent={} | model={} | correlationId={} | {}",
                    agent, safeVal(model), safeVal(correlationId), metaStr);
        } catch (Exception e) {
            // Observability must never break business execution
        }
    }

    /**
     * Logs successful completion of an AI agent evaluation.
     */
    public void logAgentComplete(String agent, String correlationId, String model, long durationMs,
                                 String outcome, String summary, Map<String, Object> metrics) {
        try {
            String metricsStr = formatMetadata(metrics);
            String sanitizedSummary = AiLogSanitizer.truncate(summary, 200);
            log.info("[AI_OBSERVABILITY] event=AGENT_COMPLETE [AI:COMPLETE] agent={} | outcome={} | durationMs={}ms | summary=\"{}\" | model={} | correlationId={} | {}",
                    agent, outcome, durationMs, sanitizedSummary, safeVal(model), safeVal(correlationId), metricsStr);
        } catch (Exception e) {
            // Observability must never break business execution
        }
    }

    /**
     * Logs an agent failure or unexpected error that required fallback routing.
     */
    public void logAgentFailure(String agent, String correlationId, String model, long durationMs,
                                String errorType, String errorMessage, String fallbackAction) {
        try {
            String sanitizedMsg = AiLogSanitizer.truncate(errorMessage, 200);
            log.error("[AI_OBSERVABILITY] event=AGENT_FAILURE [AI:FAIL] agent={} | errorType={} | durationMs={}ms | fallbackAction={} | error=\"{}\" | model={} | correlationId={}",
                    agent, errorType, durationMs, fallbackAction, sanitizedMsg, safeVal(model), safeVal(correlationId));
        } catch (Exception e) {
            // Observability must never break business execution
        }
    }

    /**
     * Logs the start of a tool / external API invocation.
     */
    public void logToolStart(String agent, String correlationId, String tool, Map<String, Object> params) {
        try {
            String paramsStr = formatMetadata(params);
            log.info("[AI_OBSERVABILITY] event=TOOL_START [AI:TOOL] agent={} | tool={} | correlationId={} | {}",
                    agent, tool, safeVal(correlationId), paramsStr);
        } catch (Exception e) {
            // Observability must never break business execution
        }
    }

    /**
     * Logs successful completion of a tool / external API invocation.
     */
    public void logToolComplete(String agent, String correlationId, String tool, long durationMs,
                                String status, Map<String, Object> outputMetadata) {
        try {
            String metaStr = formatMetadata(outputMetadata);
            log.info("[AI_OBSERVABILITY] event=TOOL_COMPLETE [AI:TOOL] agent={} | tool={} | status={} | durationMs={}ms | correlationId={} | {}",
                    agent, tool, status, durationMs, safeVal(correlationId), metaStr);
        } catch (Exception e) {
            // Observability must never break business execution
        }
    }

    /**
     * Logs a tool / external invocation failure (e.g. telemetry endpoint down, database timeout).
     */
    public void logToolFailure(String agent, String correlationId, String tool, long durationMs,
                               String errorType, String errorMessage) {
        try {
            String sanitizedMsg = AiLogSanitizer.truncate(errorMessage, 200);
            log.warn("[AI_OBSERVABILITY] event=TOOL_FAILURE [AI:TOOL_FAIL] agent={} | tool={} | errorType={} | durationMs={}ms | error=\"{}\" | correlationId={}",
                    agent, tool, errorType, durationMs, sanitizedMsg, safeVal(correlationId));
        } catch (Exception e) {
            // Observability must never break business execution
        }
    }

    /**
     * Logs a call to the underlying Gemini LLM.
     */
    public void logModelCall(String agent, String correlationId, String model, long durationMs,
                             String status, int promptCharLength, int responseCharLength) {
        try {
            log.info("[AI_OBSERVABILITY] event=MODEL_CALL [AI:MODEL] agent={} | model={} | status={} | durationMs={}ms | promptChars={} responseChars={} | correlationId={}",
                    agent, safeVal(model), status, durationMs, promptCharLength, responseCharLength, safeVal(correlationId));
        } catch (Exception e) {
            // Observability must never break business execution
        }
    }

    /**
     * Logs a failure communicating with the LLM API (e.g., HTTP error, timeout, unconfigured).
     */
    public void logModelFailure(String agent, String correlationId, String model, long durationMs,
                                String errorType, String errorMessage, boolean fallbackTriggered) {
        try {
            String sanitizedMsg = AiLogSanitizer.truncate(errorMessage, 200);
            log.warn("[AI_OBSERVABILITY] event=MODEL_FAILURE [AI:MODEL_FAIL] agent={} | model={} | errorType={} | durationMs={}ms | fallbackTriggered={} | error=\"{}\" | correlationId={}",
                    agent, safeVal(model), errorType, durationMs, fallbackTriggered, sanitizedMsg, safeVal(correlationId));
        } catch (Exception e) {
            // Observability must never break business execution
        }
    }

    /**
     * Logs when a fallback decision is triggered (e.g., rule-based fallback, human-only fallback).
     */
    public void logFallbackDecision(String agent, String correlationId, String reason, String fallbackRecommendation) {
        try {
            String sanitizedReason = AiLogSanitizer.truncate(reason, 200);
            log.warn("[AI_OBSERVABILITY] event=FALLBACK_DECISION [AI:FALLBACK] agent={} | recommendation={} | reason=\"{}\" | correlationId={}",
                    agent, fallbackRecommendation, sanitizedReason, safeVal(correlationId));
        } catch (Exception e) {
            // Observability must never break business execution
        }
    }

    /**
     * Logs when an LLM response fails validation (e.g., unparseable JSON, invalid schema, unknown enum).
     */
    public void logValidationFailure(String agent, String correlationId, String validationReason, String rawResponseSnippet) {
        try {
            String snippet = AiLogSanitizer.truncate(rawResponseSnippet, 150);
            log.warn("[AI_OBSERVABILITY] event=VALIDATION_FAILURE [AI:VALIDATION_FAIL] agent={} | reason=\"{}\" | snippet=\"{}\" | correlationId={}",
                    agent, validationReason, snippet, safeVal(correlationId));
        } catch (Exception e) {
            // Observability must never break business execution
        }
    }

    private String safeVal(String val) {
        return val != null && !val.isBlank() ? val : "N/A";
    }

    private String formatMetadata(Map<String, Object> map) {
        if (map == null || map.isEmpty()) {
            return "";
        }
        StringJoiner joiner = new StringJoiner(" ");
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            if (entry.getValue() != null) {
                String strVal = AiLogSanitizer.sanitize(String.valueOf(entry.getValue()));
                if (strVal.contains(" ") || strVal.contains("\"")) {
                    joiner.add(entry.getKey() + "=\"" + strVal.replace("\"", "'") + "\"");
                } else {
                    joiner.add(entry.getKey() + "=" + strVal);
                }
            }
        }
        return joiner.toString();
    }
}
