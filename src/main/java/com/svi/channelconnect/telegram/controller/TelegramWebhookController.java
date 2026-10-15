package com.svi.channelconnect.telegram.controller;

import com.svi.channelconnect.telegram.model.TelegramDispatchResult;
import com.svi.channelconnect.telegram.service.TelegramWebhookService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnProperty(prefix = "telegram", name = "enabled", havingValue = "true")
@RequestMapping("/webhooks/telegram")
public class TelegramWebhookController {
	private final TelegramWebhookService webhookService;

	public TelegramWebhookController(TelegramWebhookService webhookService) {
		this.webhookService = webhookService;
	}

	@PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<Void> receive(@RequestBody byte[] rawBody) {
		if (webhookService.handle(rawBody) == TelegramDispatchResult.OVERLOADED) {
			return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
		}
		return ResponseEntity.ok().build();
	}
}
