package com.example.chatbot;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Sliding-window limiter. In-memory = single instance only; use Redis/Bucket4j if you scale out. */
// @Component
// class RateLimiter {
//     private final Map<String, Deque<Long>> hits = new ConcurrentHashMap<>();

//     boolean allow(String key, int max, long windowMs) {
//         long now = System.currentTimeMillis();
//         Deque<Long> q = hits.computeIfAbsent(key, k -> new ArrayDeque<>());
//         synchronized (q) {
//             while (!q.isEmpty() && (now - q.peekFirst()) > windowMs) q.pollFirst();
//             if (q.size() >= max) return false;
//             q.addLast(now);
//             return true;
//         }
//     }
// }

@Component
class RateLimiter {

    private static final class Bucket {
        final Deque<Long> q = new ArrayDeque<>();
        long windowMs;          // guarded by synchronized(this)
        boolean removed;        // guarded by synchronized(this)
    }

    private final Map<String, Bucket> hits = new ConcurrentHashMap<>();

    boolean allow(String key, int max, long windowMs) {
        long now = System.currentTimeMillis();
        while (true) {
            Bucket b = hits.computeIfAbsent(key, k -> new Bucket());
            synchronized (b) {
                if (b.removed) {
                    continue; // evicted between lookup and lock: fetch a fresh bucket
                }
                b.windowMs = windowMs;
                prune(b.q, now, windowMs);
                if (b.q.size() >= max) {
                    return false;
                }
                b.q.addLast(now);
                return true;
            }
        }
    }

    /** Drops buckets whose hits have all expired. Runs every 5 minutes. */
    @Scheduled(fixedDelay = 300_000)
    void cleanup() {
        long now = System.currentTimeMillis();
        hits.entrySet().removeIf(e -> {
            Bucket b = e.getValue();
            synchronized (b) {
                prune(b.q, now, b.windowMs);
                if (b.q.isEmpty()) {
                    b.removed = true;
                    return true;
                }
                return false;
            }
        });
    }

    private static void prune(Deque<Long> q, long now, long windowMs) {
        while (!q.isEmpty() && (now - q.peekFirst()) > windowMs) {
            q.pollFirst();
        }
    }
}
