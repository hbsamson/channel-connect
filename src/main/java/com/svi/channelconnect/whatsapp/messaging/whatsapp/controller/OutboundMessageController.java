package com.svi.channelconnect.whatsapp.messaging.whatsapp.controller;

import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.svi.channelconnect.whatsapp.messaging.whatsapp.dto.request.OutboundTemplateMessageRequest;
import com.svi.channelconnect.whatsapp.messaging.whatsapp.dto.response.OutboundMessageResponse;
import com.svi.channelconnect.whatsapp.messaging.whatsapp.service.OutboundMessageService;

@RestController
@Profile({"local", "test"})
@RequestMapping("/api/v1/whatsapp/messages")
public class OutboundMessageController {
	private final OutboundMessageService outboundMessageService;

	public OutboundMessageController(OutboundMessageService outboundMessageService) {
		this.outboundMessageService = outboundMessageService;
	}

	@PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<OutboundMessageResponse> send(
			@RequestHeader(name = "Idempotency-Key") String idempotencyKey,
			@Valid @RequestBody OutboundTemplateMessageRequest request) {
		OutboundMessageResponse response = outboundMessageService.sendTemplateMessage(idempotencyKey, request);
		return ResponseEntity.accepted().body(response);
	}
}
