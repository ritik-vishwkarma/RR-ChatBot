package com.example.chatbot;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface RefreshFamilyRepo extends JpaRepository<RefreshFamily, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select f from RefreshFamily f where f.id = :id")
    Optional<RefreshFamily> findByIdForUpdate(@Param("id") UUID id);
}
