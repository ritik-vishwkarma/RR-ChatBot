package com.example.chatbot;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChatRepo extends JpaRepository<Domain.Chat, UUID> {
    List<Domain.Chat> findByOwnerOrderByCreatedAtDesc(String owner);
    Optional<Domain.Chat> findByIdAndOwner(UUID id, String owner);  
}
