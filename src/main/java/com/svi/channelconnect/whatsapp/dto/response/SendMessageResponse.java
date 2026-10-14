package com.svi.channelconnect.whatsapp.dto.response;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record SendMessageResponse(List<Message> messages) {
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record Message(String id) {
	}

	public String firstMessageId() {
		if (messages == null || messages.isEmpty() || messages.getFirst() == null) {
			return null;
		}
		return messages.getFirst().id();
	}
}
