package com.svi.channelconnect.whatsapp.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.svi.channelconnect.whatsapp.exception.InvalidWebhookSignatureException;
import com.svi.channelconnect.whatsapp.security.WebhookSignatureVerifier;
import com.svi.channelconnect.whatsapp.service.WhatsAppWebhookService;

@RestController
@RequestMapping("/webhooks/whatsapp")
public class WhatsAppWebhookController {
	private final WebhookSignatureVerifier signatureVerifier;
	private final WhatsAppWebhookService webhookService;

	public WhatsAppWebhookController(WebhookSignatureVerifier signatureVerifier,
			WhatsAppWebhookService webhookService) {
		this.signatureVerifier = signatureVerifier;
		this.webhookService = webhookService;
	}

	@GetMapping(produces = MediaType.TEXT_PLAIN_VALUE)
	public ResponseEntity<String> verify(
			@RequestParam(name = "hub.mode", required = false) String mode,
			@RequestParam(name = "hub.verify_token", required = false) String verifyToken,
			@RequestParam(name = "hub.challenge", required = false) String challenge) {
		if (!StringUtils.hasText(mode) || !StringUtils.hasText(challenge)) {
			return ResponseEntity.badRequest().body("Invalid verification request");
		}
		if (!"subscribe".equals(mode) || !signatureVerifier.isVerificationTokenValid(verifyToken)) {
			return ResponseEntity.status(HttpStatus.FORBIDDEN).body("Verification failed");
		}
		return ResponseEntity.ok(challenge);
	}

	@PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<Void> receive(
			@RequestHeader(name = "X-Hub-Signature-256", required = false) String signature,
			@RequestBody byte[] rawBody) {
		if (!signatureVerifier.isSignatureValid(rawBody, signature)) {
			throw new InvalidWebhookSignatureException();
		}

		WhatsAppWebhookService.WebhookHandlingResult result = webhookService.handle(rawBody);
		if (result.overloaded() > 0) {
			return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
		}
		if (result.accepted() > 0) {
			return ResponseEntity.accepted().build();
		}
		return ResponseEntity.ok().build();
	}
}
