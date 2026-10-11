
package com.example.chatbot;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
class AiWebhook {
    private final RestClient http;
    private final ObjectMapper objectMapper;
    private final String url;

    AiWebhook(
            @Value("${ai.webhook.url}") String url,
            @Value("${ai.webhook.secret}") String secret,
            ObjectMapper objectMapper) {

        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(60));

        this.url = url;
        this.objectMapper = objectMapper;
        this.http = RestClient.builder()
                .requestFactory(factory)
                .defaultHeader("X-Webhook-Secret", secret)
                .build();
    }

    String ask(List<Map<String, String>> history) {
        final String requestJson;

        try {
            requestJson = objectMapper
                .writeValueAsString(
                    Map.of("messages", history
                ));

        } catch (JsonProcessingException e) {
            throw new 
                IllegalStateException(
                    "Failed to serialize AI webhook request", e
                );
        }

        String responseBody = http
                .post()
                .uri(url)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .body(requestJson)
                .retrieve()
                .body(String.class);

        if (responseBody == null || responseBody.isBlank()) {
            throw new 
                IllegalStateException(
                    "AI webhook returned an empty response"
                );
        }

        final Map<?, ?> response;
        try {
            response = objectMapper.readValue(responseBody, Map.class);
        
        } catch (JsonProcessingException e) {
            throw new 
                IllegalStateException(
                    "AI webhook returned invalid JSON", e
                );
        }

        Object reply = response.get("reply");
        if (!(reply instanceof String text) || text.isBlank()) {
            throw new 
                IllegalStateException(
                    "AI webhook response has no valid reply field"
                );
        }

        return text;
    }
}
