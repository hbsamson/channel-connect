package com.svi.channelconnect.sms.exception;

import java.time.Instant;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.http.converter.HttpMessageNotReadableException;

@RestControllerAdvice(basePackages = "com.svi.channelconnect.sms.controller")
public class SmsExceptionHandler {
	@ExceptionHandler(SmsException.class)
	ResponseEntity<Error> sms(SmsException exception) {
		return error(switch (exception.reason()) {
			case INVALID -> 400; case UNAUTHORIZED -> 401; case FORBIDDEN -> 403;
			case CONFLICT -> 409; case CAPACITY -> 503; case NOT_FOUND -> 404; case EXPIRED -> 422;
		});
	}
	@ExceptionHandler({IllegalArgumentException.class, MethodArgumentNotValidException.class, MissingRequestHeaderException.class, HttpMessageNotReadableException.class})
	ResponseEntity<Error> invalid() { return error(400); }
	@ExceptionHandler({java.io.IOException.class, InterruptedException.class})
	ResponseEntity<Error> unavailable(Exception exception) {
		if (exception instanceof InterruptedException) Thread.currentThread().interrupt(); return error(503);
	}
	@ExceptionHandler(Exception.class)
	ResponseEntity<Error> unexpected() { return error(500); }
	private ResponseEntity<Error> error(int status) { return ResponseEntity.status(status).body(new Error(Instant.now(), status, "SMS request could not be completed")); }
	public record Error(Instant timestamp, int status, String message) { }
}
