package com.example.chatbot;

import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
class ApiExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);
    record ErrorBody(boolean success, String message, Map<String, String> errors) {}

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ErrorBody> validation(MethodArgumentNotValidException ex) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (FieldError e : ex.getBindingResult().getFieldErrors()) {
            fields.putIfAbsent(e.getField(), e.getDefaultMessage());
        }

        return ResponseEntity
                .badRequest()
                .body(
                    new ErrorBody(
                        false, 
                        "Request validation failed", 
                        fields
                    )
                );
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ErrorBody> typeMismatch(MethodArgumentTypeMismatchException ex) {
        return ResponseEntity
                .badRequest()
                .body(
                    new ErrorBody(
                        false, 
                        "Invalid request parameter", 
                        null
                    )
                );
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ErrorBody> malformed(HttpMessageNotReadableException ex) {
        return ResponseEntity
                .badRequest()
                .body(
                    new ErrorBody(
                        false, 
                        "Malformed JSON request", 
                        null
                    )
                );
    }

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<ErrorBody> status(ResponseStatusException ex) {
        HttpStatus status = HttpStatus.valueOf(ex.getStatusCode().value());
        String message = switch (status) {
            case BAD_REQUEST -> "Invalid request";
            case UNAUTHORIZED -> "Authentication required or credentials invalid";
            case FORBIDDEN -> "Request forbidden";
            case NOT_FOUND -> "Resource not found";
            case CONFLICT -> "Request conflicts with current state";
            case TOO_MANY_REQUESTS -> "Too many requests; try again later";
            case BAD_GATEWAY, GATEWAY_TIMEOUT, SERVICE_UNAVAILABLE -> "Dependent service unavailable";
            default -> status.is5xxServerError() ? "Internal server error" : "Request failed";
        };

        return ResponseEntity
                .status(status)
                .body(
                    new ErrorBody(
                        false, 
                        message, 
                        null
                    )
                );
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ErrorBody> denied(AccessDeniedException ex) {
        return ResponseEntity
                .status(HttpStatus.FORBIDDEN)
                .body(
                    new ErrorBody(
                        false, 
                        "Request forbidden", 
                        null
                    )
                );
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorBody> unexpected(Exception ex) {
        log.error("Unhandled API exception", ex);
        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(
                    new ErrorBody(
                        false, 
                        "Internal server error", 
                        null
                    )
                );
    }
}
