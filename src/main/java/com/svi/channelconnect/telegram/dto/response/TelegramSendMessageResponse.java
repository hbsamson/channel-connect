package com.svi.channelconnect.telegram.dto.response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TelegramSendMessageResponse(
		Boolean ok,
		Message result,
		@JsonProperty("error_code") Integer errorCode
) {
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record Message(@JsonProperty("message_id") Long messageId) {
	}
}
