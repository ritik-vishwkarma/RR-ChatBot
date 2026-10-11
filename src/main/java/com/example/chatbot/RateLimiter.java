package com.example.chatbot;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/** Sliding-window limiter. In-memory = single instance only; use Redis/Bucket4j if you scale out. */
@Component
class RateLimiter {
    private final Map<String, Deque<Long>> hits = new ConcurrentHashMap<>();

    boolean allow(String key, int max, long windowMs) {
        long now = System.currentTimeMillis();
        Deque<Long> q = hits.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (q) {
            while (!q.isEmpty() && (now - q.peekFirst()) > windowMs) q.pollFirst();
            if (q.size() >= max) return false;
            q.addLast(now);
            return true;
        }
    }
}
