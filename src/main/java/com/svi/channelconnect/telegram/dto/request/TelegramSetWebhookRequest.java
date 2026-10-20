package com.svi.channelconnect.telegram.dto.request;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

public record TelegramSetWebhookRequest(
		String url,
		@JsonProperty("secret_token") String secretToken,
		@JsonProperty("allowed_updates") List<String> allowedUpdates
) {
	@Override
	public String toString() {
		return "TelegramSetWebhookRequest[url=<redacted>, secretToken=<redacted>, allowedUpdates=" + allowedUpdates + "]";
	}
}
