package com.svi.channelconnect.sms.controller;

import java.net.URI;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.svi.channelconnect.sms.model.SmsSubmission;
import com.svi.channelconnect.sms.service.SmsTmbgDemoService;

@RestController
@Profile({"local", "test"})
@ConditionalOnProperty(name = "sms.enabled", havingValue = "true")
@RequestMapping("/api/v1/sms/campaign")
public class SmsTmbgDemoController {
	private final SmsTmbgDemoService demo;

	public SmsTmbgDemoController(SmsTmbgDemoService demo) { this.demo = demo; }

	@PostMapping(path = "/start", consumes = "application/json", produces = "application/json")
	public ResponseEntity<SmsSubmission> start(@Valid @RequestBody Request request) {
		SmsSubmission result = demo.start(request.accountId(), request.recipient());
		return ResponseEntity.accepted().location(URI.create("/api/v1/sms/messages/" + result.messageId())).body(result);
	}

	public record Request(@NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") String accountId,
			@Size(max = 32) String recipient) {
		@Override public String toString() { return "SmsCampaignRequest[payload=<redacted>]"; }
	}
}
