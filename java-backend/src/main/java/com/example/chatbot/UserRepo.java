package com.example.chatbot;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepo extends JpaRepository<Domain.UserAccount, UUID> {
    Optional<Domain.UserAccount> findByEmail(String email);
    boolean existsByEmail(String email);
}
