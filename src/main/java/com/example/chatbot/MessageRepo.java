package com.example.chatbot;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MessageRepo extends JpaRepository<Domain.Message, Long> {
    List<Domain.Message> findByChatIdOrderByIdAsc(UUID chatId);
    List<Domain.Message> findTop20ByChatIdOrderByIdDesc(UUID chatId);
}
