package com.svi.channelconnect.whatsapp.messaging.messenger.exception;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(basePackages = "com.svi.messaging.messenger.controller")
public class ApiExceptionHandler {
    @ExceptionHandler(InvalidWebhookSignatureException.class)
    ResponseEntity<Map<String, String>> invalidSignature() {
        return response(HttpStatus.FORBIDDEN, "Invalid webhook signature");
    }

    @ExceptionHandler(MalformedWebhookException.class)
    ResponseEntity<Map<String, String>> malformedPayload() {
        return response(HttpStatus.BAD_REQUEST, "Malformed webhook payload");
    }

    @ExceptionHandler(QueueUnavailableException.class)
    ResponseEntity<Map<String, String>> queueUnavailable() {
        return response(HttpStatus.SERVICE_UNAVAILABLE, "Webhook processing is temporarily unavailable");
    }

    private ResponseEntity<Map<String, String>> response(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of("error", message));
    }
}
