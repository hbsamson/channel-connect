package com.svi.channelconnect.whatsapp.dto.request;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record WhatsAppWebhookRequest(String object, List<Entry> entry) {

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record Entry(String id, List<Change> changes) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record Change(String field, Value value) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record Value(
			@JsonProperty("messaging_product") String messagingProduct,
			Metadata metadata,
			List<Message> messages,
			List<Status> statuses
	) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record Metadata(
			@JsonProperty("display_phone_number") String displayPhoneNumber,
			@JsonProperty("phone_number_id") String phoneNumberId
	) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record Message(
			String from,
			String id,
			String timestamp,
			String type,
			Text text
	) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record Text(String body) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record Status(String id, String status) {
	}
}
