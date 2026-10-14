package com.svi.channelconnect.sms.controller;

import java.net.URI;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.svi.channelconnect.sms.exception.SmsException;
import com.svi.channelconnect.sms.model.SmsSubmission;
import com.svi.channelconnect.sms.persistence.SmsMessageRepository;
import com.svi.channelconnect.sms.service.SmsService;

@RestController
@Profile({"local", "test"})
@ConditionalOnProperty(name = "sms.enabled", havingValue = "true")
@RequestMapping("/api/v1/sms/messages")
public class SmsMessageController {
	private final SmsService sms;
	private final SmsMessageRepository store;
	public SmsMessageController(SmsService sms, SmsMessageRepository store) { this.sms = sms; this.store = store; }
	@PostMapping(consumes = "application/json", produces = "application/json")
	public ResponseEntity<SmsSubmission> send(@RequestHeader("Idempotency-Key") String key, @Valid @RequestBody Request request) {
		SmsSubmission result = sms.submit(key, request.accountId(), request.recipient(), request.content());
		return ResponseEntity.accepted().location(URI.create("/api/v1/sms/messages/" + result.messageId())).body(result);
	}
	@GetMapping("/{id}")
	public Status status(@PathVariable String id) {
		var message = store.outbound(id).orElseThrow(() -> new SmsException(SmsException.Reason.NOT_FOUND));
		return new Status(message.id(), message.status(), "UNCONFIRMED", store.errorCode(message.id()));
	}
	public record Request(@NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") String accountId,
			@NotBlank @Size(max = 32) String recipient, @NotBlank @Size(max = 3000) String content) {
		@Override public String toString() { return "SmsRequest[payload=<redacted>]"; }
	}
	public record Status(String messageId, String status, String handsetDelivery, String errorCode) { }
}
