package com.example.chatbot;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Lockable aggregate for one login session's refresh-token family.
 * Every refresh/revoke operation must lock this row before locking token rows.
 */
@Entity
@Table(name = "refresh_families")
public class RefreshFamily {

    @Id
    UUID id;

    @Column(nullable = false)
    Instant createdAt = Instant.now();

    Instant revokedAt;

    protected RefreshFamily() {}

    RefreshFamily(UUID id) {
        this.id = id;
    }
}
