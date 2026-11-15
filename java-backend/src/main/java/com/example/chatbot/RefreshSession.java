package com.example.chatbot;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "refresh_sessions", indexes = {
    @Index(name = "idx_refresh_token_hash", columnList = "tokenHash"),
    @Index(name = "idx_refresh_family", columnList = "familyId")
})
public class RefreshSession {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    UUID id;

    @Column(nullable = false)
    UUID familyId;

    @Column(nullable = false, length = 64, unique = true)
    String tokenHash;

    @Column(nullable = false)
    String userEmail;

    @Column(nullable = false)
    Instant createdAt = Instant.now();

    @Column(nullable = false)
    Instant expiresAt;

    Instant revokedAt;

    String replacedByHash;

    protected RefreshSession() {}

    RefreshSession(
        UUID familyId,
        String tokenHash,
        String userEmail,
        Instant expiresAt
    ) {
        this.familyId = familyId;
        this.tokenHash = tokenHash;
        this.userEmail = userEmail;
        this.expiresAt = expiresAt;
    }
}