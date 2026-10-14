package com.mrlbs.ai.observability;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utility for sanitizing and redacting sensitive data from AI agent diagnostic logs.
 * Ensures passwords, API keys, bearer tokens, connection credentials, and personal
 * identifiers are never leaked to log files or consoles (NFR-8.2).
 */
public final class AiLogSanitizer {

    private static final Pattern API_KEY_PATTERN = Pattern.compile("(?i)(api[_-]?key|x-goog-api-key|password|secret|token|credential)[\"':=\\s]+([a-zA-Z0-9_.-]{4,})");
    private static final Pattern BEARER_PATTERN = Pattern.compile("(?i)bearer\\s+[a-zA-Z0-9_.-]+");
    private static final Pattern EMAIL_PATTERN = Pattern.compile("(?i)\\b([a-zA-Z0-9_.+-])[a-zA-Z0-9_.+-]*@([a-zA-Z0-9-]+\\.[a-zA-Z0-9-.]+)");
    private static final Pattern CONNECTION_URL_CREDENTIALS = Pattern.compile("(?i)(https?|amqps?|postgres)://[^:@/\\s]+:([^@/\\s]+)@");
    private static final Pattern GEMINI_RAW_KEY = Pattern.compile("AIzaSy[a-zA-Z0-9_-]{28,45}");

    private AiLogSanitizer() {}

    /**
     * Sanitizes an arbitrary text message by redacting secrets and masking emails.
     */
    public static String sanitize(String message) {
        if (message == null || message.isBlank()) {
            return message;
        }

        String result = message;

        // 1. Redact credentials in connection URLs
        result = CONNECTION_URL_CREDENTIALS.matcher(result).replaceAll("$1://[REDACTED_USER]:[REDACTED_SECRET]@");

        // 2. Redact Authorization Bearer tokens
        result = BEARER_PATTERN.matcher(result).replaceAll("Bearer [REDACTED]");

        // 3. Redact raw Gemini API keys
        result = GEMINI_RAW_KEY.matcher(result).replaceAll("[REDACTED_GEMINI_KEY]");

        // 4. Redact API keys and secrets
        Matcher keyMatcher = API_KEY_PATTERN.matcher(result);
        StringBuffer sb = new StringBuffer();
        while (keyMatcher.find()) {
            String keyName = keyMatcher.group(1);
            keyMatcher.appendReplacement(sb, Matcher.quoteReplacement(keyName + "=[REDACTED]"));
        }
        keyMatcher.appendTail(sb);
        result = sb.toString();

        // 5. Mask email addresses (e.g. user@iitk.ac.in -> u***@iitk.ac.in)
        result = EMAIL_PATTERN.matcher(result).replaceAll("$1***@$2");

        return result;
    }

    /**
     * Truncates text to a maximum length for diagnostic logging without dumping entire payloads.
     */
    public static String truncate(String text, int maxLength) {
        if (text == null) {
            return null;
        }
        String sanitized = sanitize(text.trim());
        if (sanitized.length() <= maxLength) {
            return sanitized;
        }
        return sanitized.substring(0, maxLength) + "... [TRUNCATED, len=" + sanitized.length() + "]";
    }
}
