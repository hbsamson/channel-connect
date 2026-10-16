package com.svi.channelconnect.telegram.dto.request;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record TelegramSendTextMessageRequest(
		@JsonProperty("chat_id") long chatId,
		@JsonProperty("message_thread_id") Long messageThreadId,
		String text
) {
	@Override
	public String toString() {
		return "TelegramSendTextMessageRequest[chatId=<redacted>, messageThreadId="
				+ messageThreadId + ", text=<redacted>]";
	}
}
