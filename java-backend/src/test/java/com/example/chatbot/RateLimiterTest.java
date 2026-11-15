package com.example.chatbot;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RateLimiterTest {
    @Test
    void allowsConfiguredNumberOfRequestsThenRejectsMore() {
        RateLimiter limiter = new RateLimiter();
        String key = "unit-test-" + System.nanoTime();

        assertTrue(limiter.allow(key, 2, 60_000));
        assertTrue(limiter.allow(key, 2, 60_000));
        assertFalse(limiter.allow(key, 2, 60_000));
    }

    @Test
    void expiresHitsOutsideSlidingWindow() throws InterruptedException {
        RateLimiter limiter = new RateLimiter();
        String key = "unit-expiry-test-" + System.nanoTime();

        assertTrue(limiter.allow(key, 1, 5));
        Thread.sleep(25);
        assertTrue(limiter.allow(key, 1, 5));
    }

    @Test
    void keysAreIndependentlyLimited() {
        RateLimiter limiter = new RateLimiter();
        String suffix = Long.toString(System.nanoTime());

        assertTrue(limiter.allow("user-a-" + suffix, 1, 60_000));
        assertFalse(limiter.allow("user-a-" + suffix, 1, 60_000));
        assertTrue(limiter.allow("user-b-" + suffix, 1, 60_000));
    }
}
