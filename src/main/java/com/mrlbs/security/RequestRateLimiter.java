package com.mrlbs.security;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

@Component
public class RequestRateLimiter {
    private static final long WINDOW = Duration.ofMinutes(1).toNanos();
    private static final int MAX_BUCKETS = 20_000;
    private final Map<String, Bucket> buckets = new HashMap<>();
    private long lastCleanup = System.nanoTime();

    public synchronized boolean allow(String key, int limit) {
        long now = System.nanoTime();
        if (now - lastCleanup >= WINDOW) {
            buckets.values().removeIf(bucket -> now - bucket.started >= WINDOW);
            lastCleanup = now;
        }
        Bucket bucket = buckets.get(key);
        if (bucket == null || now - bucket.started >= WINDOW) {
            if (bucket == null && buckets.size() >= MAX_BUCKETS) {
                return false;
            }
            bucket = new Bucket(now);
            buckets.put(key, bucket);
        }
        if (bucket.count >= limit) {
            return false;
        }
        bucket.count++;
        return true;
    }

    private static final class Bucket {
        final long started;
        int count;

        Bucket(long started) {
            this.started = started;
        }
    }
}
