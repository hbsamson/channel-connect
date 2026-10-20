package com.svi.channelconnect.telegram.dto.response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TelegramSetWebhookResponse(
		Boolean ok,
		Boolean result,
		@JsonProperty("error_code") Integer errorCode
) {
}
