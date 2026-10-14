package com.svi.channelconnect.dealership.controller;

import java.util.Map;

import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = DemoController.class)
public class DemoExceptionHandler {
	@ExceptionHandler({IllegalArgumentException.class, com.svi.channelconnect.dealership.model.DemoRequestException.class,
			MethodArgumentNotValidException.class, HttpMessageNotReadableException.class})
	public ResponseEntity<Map<String, String>> invalid() {
		return ResponseEntity.badRequest().body(Map.of("error", "Invalid demo request; check identifiers, permissions, and configuration."));
	}
	@ExceptionHandler(DataAccessException.class)
	public ResponseEntity<Map<String, String>> conflict() {
		return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "The requested change could not be stored; check existing identities and appointments."));
	}
}
