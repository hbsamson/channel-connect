package com.svi.channelconnect.telegram.controller;

import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.svi.channelconnect.telegram.dto.request.TelegramOutboundMessageRequest;
import com.svi.channelconnect.telegram.dto.response.TelegramOutboundMessageResponse;
import com.svi.channelconnect.telegram.service.TelegramOutboundMessageService;

@RestController
@Profile({"local", "test"})
@ConditionalOnProperty(name = {"telegram.enabled", "telegram.campaign.enabled"}, havingValue = "true")
@RequestMapping("/api/v1/telegram")
public class TelegramMessageController {
	private final TelegramOutboundMessageService messages;

	public TelegramMessageController(TelegramOutboundMessageService messages) {
		this.messages = messages;
	}

	@PostMapping(path = {"/messages", "/send"}, consumes = "application/json", produces = "application/json")
	public ResponseEntity<TelegramOutboundMessageResponse> send(@RequestHeader("Idempotency-Key") String key,
			@Valid @RequestBody TelegramOutboundMessageRequest request) {
		return ResponseEntity.accepted().body(messages.send(key, request));
	}
}
