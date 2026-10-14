package com.svi.channelconnect.whatsapp.exception;

import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.svi.channelconnect.whatsapp.dto.response.ApiErrorResponse;

@RestControllerAdvice(basePackages = "com.svi.channelconnect.whatsapp.controller")
public class GlobalExceptionHandler {
	private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

	@ExceptionHandler(InvalidWebhookSignatureException.class)
	ResponseEntity<ApiErrorResponse> handleInvalidSignature() {
		return error(HttpStatus.UNAUTHORIZED, "Webhook authentication failed");
	}

	@ExceptionHandler(MalformedWebhookException.class)
	ResponseEntity<ApiErrorResponse> handleMalformedWebhook() {
		return error(HttpStatus.BAD_REQUEST, "Malformed webhook payload");
	}

	@ExceptionHandler(OutboundAuthenticationException.class)
	ResponseEntity<ApiErrorResponse> handleOutboundAuthentication() {
		return error(HttpStatus.UNAUTHORIZED, "Outbound API authentication failed");
	}

	@ExceptionHandler(OutboundAuthorizationException.class)
	ResponseEntity<ApiErrorResponse> handleOutboundAuthorization() {
		return error(HttpStatus.FORBIDDEN, "Outbound request is not permitted");
	}

	@ExceptionHandler({InvalidOutboundRequestException.class,
			MethodArgumentNotValidException.class, HttpMessageNotReadableException.class,
			MissingRequestHeaderException.class})
	ResponseEntity<ApiErrorResponse> handleInvalidOutboundRequest() {
		return error(HttpStatus.BAD_REQUEST, "Invalid outbound message request");
	}

	@ExceptionHandler(DuplicateOutboundSubmissionException.class)
	ResponseEntity<ApiErrorResponse> handleDuplicateOutboundSubmission(
			DuplicateOutboundSubmissionException exception) {
		return error(HttpStatus.CONFLICT, exception.getMessage());
	}

	@ExceptionHandler(OutboundCapacityException.class)
	ResponseEntity<ApiErrorResponse> handleOutboundCapacity() {
		return error(HttpStatus.SERVICE_UNAVAILABLE, "Outbound service is temporarily unavailable");
	}

	@ExceptionHandler(WhatsAppApiException.class)
	ResponseEntity<ApiErrorResponse> handleWhatsAppApiFailure(WhatsAppApiException exception) {
		log.warn("WhatsApp API request failed; category={}", exception.category());
		return switch (exception.category()) {
			case INVALID_REQUEST -> error(HttpStatus.UNPROCESSABLE_CONTENT,
					"WhatsApp rejected the template request");
			case AUTHENTICATION -> error(HttpStatus.BAD_GATEWAY,
					"WhatsApp provider authentication failed");
			case THROTTLED -> error(HttpStatus.SERVICE_UNAVAILABLE,
					"WhatsApp provider is rate limiting requests");
			case TIMEOUT -> error(HttpStatus.GATEWAY_TIMEOUT,
					"WhatsApp provider request timed out");
			case TRANSIENT, MALFORMED_RESPONSE, PERMANENT -> error(HttpStatus.BAD_GATEWAY,
					"WhatsApp provider request failed");
		};
	}

	@ExceptionHandler(Exception.class)
	ResponseEntity<ApiErrorResponse> handleUnexpected(Exception exception) {
		log.error("Unhandled request failure; type={}", exception.getClass().getSimpleName());
		return error(HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error");
	}

	private ResponseEntity<ApiErrorResponse> error(HttpStatus status, String message) {
		return ResponseEntity.status(status)
				.body(new ApiErrorResponse(Instant.now(), status.value(), message));
	}
}
