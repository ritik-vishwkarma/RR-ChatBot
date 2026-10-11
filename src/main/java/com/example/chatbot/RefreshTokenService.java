package com.example.chatbot;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class RefreshTokenService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();

    private final RefreshSessionRepo sessions;
    private final RefreshFamilyRepo families;
    private final long lifetimeSeconds;

    RefreshTokenService(
        RefreshSessionRepo sessions,
        RefreshFamilyRepo families,
        @Value("${security.jwt.refresh-seconds:864000}") 
        long lifetimeSeconds
    ) {
        this.sessions = sessions;
        this.families = families;
        if (lifetimeSeconds < 3600) {
            throw new IllegalStateException("Refresh lifetime must be at least one hour");
        }
        this.lifetimeSeconds = lifetimeSeconds;
    }

    record Issued(String rawToken, Instant expiresAt) {}
    record Rotation(
        boolean valid, 
        String email, 
        String rawToken, 
        Instant expiresAt
    ) {}

    /** Creates one independently revocable refresh-token family for a login. */
    @Transactional
    Issued create(String email) {
        Instant expiry = Instant.now().plusSeconds(lifetimeSeconds);
        UUID familyId = UUID.randomUUID();
        String raw = randomToken();

        // Persist the lock target before the first token in this family.
        families.saveAndFlush(new RefreshFamily(familyId));
        sessions.save(new RefreshSession(familyId, hash(raw), email, expiry));

        return new Issued(raw, expiry);
    }

    /**
     * Rotates one refresh token atomically.
     * Lock order is always: family row, then token row(s).
     */
    @Transactional
    Rotation rotate(String raw) {
        if (raw == null || raw.isBlank()) {
            return invalidRotation();
        }

        String oldHash = hash(raw);

        // This read discovers the family ID only. No mutable state is trusted from it.
        var discovered = sessions.findByTokenHash(oldHash);
        if (discovered.isEmpty()) {
            return invalidRotation();
        }
        UUID familyId = discovered.get().familyId;

        // Serialize refresh/refresh and refresh/logout for this login family.
        var familyResult = families.findByIdForUpdate(familyId);
        if (familyResult.isEmpty()) {
            // Legacy sessions created before refresh_families existed are not accepted.
            return invalidRotation();
        }

        RefreshFamily family = familyResult.get();
        if (family.revokedAt != null) {
            return invalidRotation();
        }

        // Lock token only after family lock; all mutating paths use this lock order.
        var tokenResult = sessions.findByTokenHashForUpdate(oldHash);
        if (tokenResult.isEmpty()) {
            return invalidRotation();
        }

        RefreshSession old = tokenResult.get();

        // Guard against inconsistent or manually altered records.
        if (!familyId.equals(old.familyId)) {
            return invalidRotation();
        }

        Instant now = Instant.now();

        if (old.revokedAt != null) {
            // Reuse of a rotated/revoked token is treated as replay; revoke this family.
            revokeFamily(family, now);
            return invalidRotation();
        }

        if (!old.expiresAt.isAfter(now)) {
            // The family uses an absolute lifetime: all descendants share this expiry.
            revokeFamily(family, now);
            return invalidRotation();
        }

        String nextRaw = randomToken();
        String nextHash = hash(nextRaw);

        old.revokedAt = now;
        old.replacedByHash = nextHash;

        sessions.save(new RefreshSession(old.familyId, nextHash, old.userEmail, old.expiresAt));

        return new Rotation(true, old.userEmail, nextRaw, old.expiresAt);
    }

    /**
     * Logout revokes the entire login family, not only the presented token.
     * If refresh wins the lock first, logout also revokes the newly rotated token.
     */
    @Transactional
    void revoke(String raw) {
        if (raw == null || raw.isBlank()) {
            return;
        }

        String tokenHash = hash(raw);
        var discovered = sessions.findByTokenHash(tokenHash);
        if (discovered.isEmpty()) {
            return;
        }

        UUID familyId = discovered.get().familyId;

        var familyResult = families.findByIdForUpdate(familyId);
        if (familyResult.isEmpty()) {
            // A legacy token without a family row can no longer be refreshed.
            return;
        }

        RefreshFamily family = familyResult.get();
        revokeFamily(family, Instant.now());
    }

    private void revokeFamily(RefreshFamily family, Instant now) {
        if (family.revokedAt == null) {
            family.revokedAt = now;
        }
        for (RefreshSession session : sessions.findByFamilyIdForUpdateOrderByIdAsc(family.id)) {
            if (session.revokedAt == null) {
                session.revokedAt = now;
            }
        }
    }

    private static Rotation invalidRotation() {
        return new Rotation(false, null, null, null);
    }

    private static String randomToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return B64.encodeToString(bytes);
    }

    private static String hash(String raw) {
        try {
            return 
                java.util.HexFormat.of()
                    .formatHex(
                        MessageDigest
                        .getInstance("SHA-256")
                        .digest(
                            raw.getBytes(StandardCharsets.UTF_8)
                        )
                    );
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
