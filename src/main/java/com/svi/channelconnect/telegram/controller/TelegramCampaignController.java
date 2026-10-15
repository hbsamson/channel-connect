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

import com.svi.channelconnect.telegram.dto.request.TelegramCampaignStartRequest;
import com.svi.channelconnect.telegram.dto.response.TelegramCampaignStartResponse;
import com.svi.channelconnect.telegram.service.TelegramCampaignService;

@RestController
@Profile({"local", "test"})
@ConditionalOnProperty(name = {"telegram.enabled", "telegram.campaign.enabled"}, havingValue = "true")
@RequestMapping("/api/v1/telegram/campaign")
public class TelegramCampaignController {
	private final TelegramCampaignService campaigns;

	public TelegramCampaignController(TelegramCampaignService campaigns) {
		this.campaigns = campaigns;
	}

	@PostMapping(path = "/start", consumes = "application/json", produces = "application/json")
	public ResponseEntity<TelegramCampaignStartResponse> start(@RequestHeader("Idempotency-Key") String key,
			@Valid @RequestBody TelegramCampaignStartRequest request) {
		return ResponseEntity.accepted().body(campaigns.start(key, request));
	}
}
