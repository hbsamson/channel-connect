package com.svi.channelconnect.whatsapp.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;

public record SendTextMessageRequest(
		@JsonProperty("messaging_product") String messagingProduct,
		@JsonProperty("recipient_type") String recipientType,
		String to,
		String type,
		Text text
) {
	public static SendTextMessageRequest to(String recipientWaId, String body) {
		return new SendTextMessageRequest(
				"whatsapp",
				"individual",
				recipientWaId,
				"text",
				new Text(false, body));
	}

	public record Text(@JsonProperty("preview_url") boolean previewUrl, String body) {
	}
}
