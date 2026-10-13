package com.mrlbs.ai;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe, in-memory sliding window rate limiter to protect server-side AI quota and compute.
 */
@Component
public class AiRateLimiter {

    private static final int DEFAULT_MAX_REQUESTS_PER_MINUTE = 15;
    private static final Duration DEFAULT_WINDOW = Duration.ofMinutes(1);

    private final ConcurrentHashMap<String, Deque<Instant>> requestLog = new ConcurrentHashMap<>();

    /**
     * Checks if a request from the given key (user identifier or IP address) is permitted.
     *
     * @param key Unique client key (e.g., user email or remote IP).
     * @return true if the request is within rate limits; false if exceeded.
     */
    public boolean tryAcquire(String key) {
        return tryAcquire(key, DEFAULT_MAX_REQUESTS_PER_MINUTE, DEFAULT_WINDOW);
    }

    public boolean tryAcquire(String key, int maxRequests, Duration window) {
        if (key == null || key.isBlank()) {
            key = "anonymous";
        }

        Instant now = Instant.now();
        Instant cutoff = now.minus(window);

        Deque<Instant> timestamps = requestLog.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (timestamps) {
            // Evict expired timestamps
            while (!timestamps.isEmpty() && timestamps.peekFirst().isBefore(cutoff)) {
                timestamps.pollFirst();
            }

            if (timestamps.size() >= maxRequests) {
                return false;
            }

            timestamps.addLast(now);
            return true;
        }
    }

    /**
     * Clears all recorded rate limits. Useful for test environments.
     */
    public void reset() {
        requestLog.clear();
    }
}
