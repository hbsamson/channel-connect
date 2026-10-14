package com.svi.channelconnect.sms.controller;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;

import com.svi.channelconnect.sms.goip.GoipRegistrationService;
import com.svi.channelconnect.sms.goip.GoipSmsProvider;

@RestController
@Profile({"local", "test"})
@ConditionalOnProperty(name = "sms.enabled", havingValue = "true")
@ConditionalOnExpression("'${sms.mode:MOCK}' != 'HTTP'")
@RequestMapping("/api/admin/sms")
public class SmsOperationsController {
	private final GoipSmsProvider provider;
	private final com.svi.channelconnect.sms.persistence.SmsMessageRepository messages;
	public SmsOperationsController(GoipSmsProvider provider, com.svi.channelconnect.sms.persistence.SmsMessageRepository messages) {
		this.provider = provider; this.messages = messages;
	}
	@GetMapping("/inbound/dead-letters")
	public List<com.svi.channelconnect.sms.persistence.SmsMessageRepository.DeadLetter> deadLetters() { return messages.deadLetters(); }
	@PostMapping("/inbound/{id}/retry")
	public Map<String, String> retry(@PathVariable String id) { messages.retryDeadLetter(id); return Map.of("status", "RECEIVED"); }
	@GetMapping("/channels")
	public List<GoipRegistrationService.Status> channels() { return provider.statuses(); }
	@GetMapping("/channels/{account}/query")
	public Map<String, String> query(@PathVariable String account) throws IOException, InterruptedException {
		return Map.of("state", provider.query(account, false));
	}
	@PostMapping("/simulator/receive")
	public Map<String, String> receive(@Valid @RequestBody SimulatedInbound request) {
		provider.simulator().inbound(request.accountId(), request.sender(), request.content());
		return Map.of("status", "SIMULATOR_SUBMITTED");
	}
	public record SimulatedInbound(@NotBlank @Size(max = 64) String accountId, @NotBlank @Size(max = 32) String sender,
			@NotBlank @Size(max = 3000) String content) {
		@Override public String toString() { return "SimulatedInbound[payload=<redacted>]"; }
	}
}
