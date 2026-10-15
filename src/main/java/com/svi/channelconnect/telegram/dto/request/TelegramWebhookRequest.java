package com.svi.channelconnect.telegram.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TelegramWebhookRequest(
		@JsonProperty("update_id") Long updateId,
		Message message
) {
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record Message(
			@JsonProperty("message_id") Long messageId,
			@JsonProperty("message_thread_id") Long messageThreadId,
			User from,
			Chat chat,
			String text
	) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record User(@JsonProperty("is_bot") Boolean bot) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record Chat(Long id, String type) {
	}
}
