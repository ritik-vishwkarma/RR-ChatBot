package com.example.chatbot;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface RefreshSessionRepo extends JpaRepository<RefreshSession, UUID> {

    // Unlocked lookup is used only to discover which family row to lock first.
    Optional<RefreshSession> findByTokenHash(String tokenHash);

    // Always call this only after acquiring the family-level lock.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from RefreshSession s where s.tokenHash = :hash")
    Optional<RefreshSession> findByTokenHashForUpdate(@Param("hash") String hash);

    // Deterministically lock all family tokens only after acquiring the family row lock.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from RefreshSession s where s.familyId = :familyId order by s.id asc")
    List<RefreshSession> findByFamilyIdForUpdateOrderByIdAsc(@Param("familyId") UUID familyId);
}
