package com.svi.channelconnect.telegram.exception;

import java.time.Instant;

import com.svi.channelconnect.telegram.dto.response.TelegramApiErrorResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(basePackages = "com.svi.channelconnect.telegram.controller")
@ConditionalOnProperty(prefix = "telegram", name = "enabled", havingValue = "true")
public class TelegramExceptionHandler {
	private static final Logger log = LoggerFactory.getLogger(TelegramExceptionHandler.class);

	@ExceptionHandler({MalformedTelegramWebhookException.class, HttpMessageNotReadableException.class})
	ResponseEntity<TelegramApiErrorResponse> handleMalformedPayload() {
		return error(HttpStatus.BAD_REQUEST, "Malformed request payload");
	}

	@ExceptionHandler(HttpMediaTypeNotSupportedException.class)
	ResponseEntity<TelegramApiErrorResponse> handleUnsupportedContentType() {
		return error(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Content-Type must be application/json");
	}

	@ExceptionHandler({MethodArgumentNotValidException.class, MissingRequestHeaderException.class})
	ResponseEntity<TelegramApiErrorResponse> handleInvalidCampaignRequest() {
		return error(HttpStatus.BAD_REQUEST, "Request fields are invalid or Idempotency-Key is missing");
	}

	@ExceptionHandler(TelegramApiException.class)
	ResponseEntity<TelegramApiErrorResponse> handleProviderFailure(TelegramApiException exception) {
		log.warn("Telegram provider request failed; category={}", exception.category());
		return switch (exception.category()) {
			case INVALID_REQUEST -> error(HttpStatus.UNPROCESSABLE_CONTENT, "Telegram rejected the outbound message");
			case AUTHENTICATION -> error(HttpStatus.BAD_GATEWAY, "Telegram provider authentication failed");
			case THROTTLED -> error(HttpStatus.SERVICE_UNAVAILABLE, "Telegram is rate limiting outbound messages");
			case TIMEOUT -> error(HttpStatus.GATEWAY_TIMEOUT, "Telegram submission outcome is unknown; inspect history before retrying");
			case TRANSIENT, MALFORMED_RESPONSE -> error(HttpStatus.BAD_GATEWAY,
					"Telegram submission outcome is unknown; inspect history before retrying");
			case PERMANENT -> error(HttpStatus.BAD_GATEWAY, "Telegram rejected the outbound message");
		};
	}

	@ExceptionHandler(TelegramCampaignException.class)
	ResponseEntity<TelegramApiErrorResponse> handleCampaignFailure(TelegramCampaignException exception) {
		return error(exception.status(), exception.getMessage());
	}

	@ExceptionHandler(DuplicateKeyException.class)
	ResponseEntity<TelegramApiErrorResponse> handleIdentityConflict() {
		return error(HttpStatus.CONFLICT, "Customer identity or campaign conflicts with an existing record");
	}

	@ExceptionHandler(Exception.class)
	ResponseEntity<TelegramApiErrorResponse> handleUnexpected(Exception exception) {
		log.error("Telegram request failed; type={}", exception.getClass().getSimpleName());
		return error(HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error");
	}

	private ResponseEntity<TelegramApiErrorResponse> error(HttpStatus status, String message) {
		return ResponseEntity.status(status)
				.body(new TelegramApiErrorResponse(Instant.now(), status.value(), message));
	}
}
