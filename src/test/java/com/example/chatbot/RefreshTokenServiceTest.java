package com.example.chatbot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RefreshTokenServiceTest {
    @Mock private RefreshSessionRepo sessions;
    @Mock private RefreshFamilyRepo families;

    private RefreshTokenService service;

    @BeforeEach
    void setUp() {
        service = new RefreshTokenService(sessions, families, 86_400);
    }

    @Test
    void missingRefreshTokenIsRejectedWithoutDatabaseLookups() {
        assertFalse(service.rotate(null).valid());
        assertFalse(service.rotate("  ").valid());
        verifyNoInteractions(sessions, families);
    }

    @Test
    void unknownRefreshTokenIsRejected() {
        when(sessions.findByTokenHash(hash("unknown-token"))).thenReturn(Optional.empty());

        RefreshTokenService.Rotation result = service.rotate("unknown-token");

        assertFalse(result.valid());
        verify(families, never()).findByIdForUpdate(any(UUID.class));
    }

    @Test
    void activeRefreshTokenRotatesAndPersistsOnlyHashOfSuccessor() {
        String raw = "original-refresh-token";
        UUID familyId = UUID.randomUUID();
        Instant expiry = Instant.now().plusSeconds(3_600);
        RefreshFamily family = new RefreshFamily(familyId);
        RefreshSession old = new RefreshSession(familyId, hash(raw), "qa@example.com", expiry);

        when(sessions.findByTokenHash(hash(raw))).thenReturn(Optional.of(old));
        when(families.findByIdForUpdate(familyId)).thenReturn(Optional.of(family));
        when(sessions.findByTokenHashForUpdate(hash(raw))).thenReturn(Optional.of(old));

        RefreshTokenService.Rotation result = service.rotate(raw);

        assertTrue(result.valid());
        assertEquals("qa@example.com", result.email());
        assertNotNull(result.rawToken());
        assertNotEquals(raw, result.rawToken());
        assertEquals(expiry, result.expiresAt());
        assertNotNull(old.revokedAt);
        assertEquals(hash(result.rawToken()), old.replacedByHash);

        ArgumentCaptor<RefreshSession> saved = ArgumentCaptor.forClass(RefreshSession.class);
        verify(sessions).save(saved.capture());
        assertEquals(hash(result.rawToken()), saved.getValue().tokenHash);
        assertNotEquals(result.rawToken(), saved.getValue().tokenHash);
        assertEquals(familyId, saved.getValue().familyId);

        InOrder order = inOrder(families, sessions);
        order.verify(families).findByIdForUpdate(familyId);
        order.verify(sessions).findByTokenHashForUpdate(hash(raw));
    }

    @Test
    void replayOfRevokedRefreshTokenRevokesEntireFamily() {
        String raw = "already-rotated-token";
        UUID familyId = UUID.randomUUID();
        Instant expiry = Instant.now().plusSeconds(3_600);
        RefreshFamily family = new RefreshFamily(familyId);
        RefreshSession old = new RefreshSession(familyId, hash(raw), "qa@example.com", expiry);
        old.revokedAt = Instant.now().minusSeconds(5);
        RefreshSession successor = new RefreshSession(familyId, hash("successor"), "qa@example.com", expiry);

        when(sessions.findByTokenHash(hash(raw))).thenReturn(Optional.of(old));
        when(families.findByIdForUpdate(familyId)).thenReturn(Optional.of(family));
        when(sessions.findByTokenHashForUpdate(hash(raw))).thenReturn(Optional.of(old));
        when(sessions.findByFamilyIdForUpdateOrderByIdAsc(familyId)).thenReturn(List.of(old, successor));

        RefreshTokenService.Rotation result = service.rotate(raw);

        assertFalse(result.valid());
        assertNotNull(family.revokedAt);
        assertNotNull(old.revokedAt);
        assertNotNull(successor.revokedAt);
    }

    @Test
    void expiredRefreshTokenIsRejectedAndFamilyRevoked() {
        String raw = "expired-token";
        UUID familyId = UUID.randomUUID();
        RefreshFamily family = new RefreshFamily(familyId);
        RefreshSession old = new RefreshSession(
                familyId, hash(raw), "qa@example.com", Instant.now().minusSeconds(10));

        when(sessions.findByTokenHash(hash(raw))).thenReturn(Optional.of(old));
        when(families.findByIdForUpdate(familyId)).thenReturn(Optional.of(family));
        when(sessions.findByTokenHashForUpdate(hash(raw))).thenReturn(Optional.of(old));
        when(sessions.findByFamilyIdForUpdateOrderByIdAsc(familyId)).thenReturn(List.of(old));

        RefreshTokenService.Rotation result = service.rotate(raw);

        assertFalse(result.valid());
        assertNotNull(family.revokedAt);
        assertNotNull(old.revokedAt);
    }

    @Test
    void familylessLegacyTokenIsRejected() {
        String raw = "legacy-token";
        UUID familyId = UUID.randomUUID();
        RefreshSession old = new RefreshSession(
                familyId, hash(raw), "qa@example.com", Instant.now().plusSeconds(3_600));

        when(sessions.findByTokenHash(hash(raw))).thenReturn(Optional.of(old));
        when(families.findByIdForUpdate(familyId)).thenReturn(Optional.empty());

        assertFalse(service.rotate(raw).valid());
        verify(sessions, never()).findByTokenHashForUpdate(any(String.class));
    }

    @Test
    void logoutWithMissingTokenDoesNothing() {
        service.revoke(null);
        service.revoke(" ");
        verifyNoInteractions(sessions, families);
    }

    private static String hash(String raw) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
