package com.example.chatbot;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

public final class Domain {
    @Entity 
    @Table(name = "users")
    public static class UserAccount {
        @Id 
        @GeneratedValue(strategy = GenerationType.UUID) 
        UUID id;

        @Column(unique = true, nullable = false) 
        String email;

        @Column(nullable = false) 
        String passwordHash;

        protected UserAccount() {

        }

        UserAccount(String email, String hash) { 
            this.email = email; 
            this.passwordHash = hash; 
        }
    }

    @Entity 
    @Table(
        name = "chats", 
        indexes = @Index(columnList = "owner")
    )
    public static class Chat {
        @Id 
        @GeneratedValue(strategy = GenerationType.UUID) 
        UUID id;   // random UUID: not guessable

        @Column(nullable = false, updatable = false) 
        String owner;     // "google:<sub>", "github:<id>" or "local:<email>"

        @Column(nullable = false) 
        String title = "New chat";

        Instant createdAt = Instant.now();

        protected Chat() {}

        Chat(String owner) { 
            this.owner = owner; 
        }
    }

    @Entity 
    @Table(
        name = "messages", 
        indexes = @Index(columnList = "chatId")
    )
    public static class Message {
        @Id 
        @GeneratedValue(strategy = GenerationType.IDENTITY) 
        Long id;

        @Column(nullable = false) 
        UUID chatId;

        @Column(nullable = false) 
        String role;

        @Column(nullable = false, columnDefinition = "text") 
        String content;

        Instant createdAt = Instant.now();

        protected Message() {

        }

        Message(UUID chatId, String role, String content) { 
            this.chatId = chatId; 
            this.role = role; 
            this.content = content; 
        }
    }
}
