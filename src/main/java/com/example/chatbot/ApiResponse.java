package com.example.chatbot;

/** Stable success envelope used by the /api/v1 contract. */
public record ApiResponse<T>(boolean success, String message, T data) {
    public static <T> ApiResponse<T> ok(String message, T data) {
        return new ApiResponse<>(true, message, data);
    }
}
