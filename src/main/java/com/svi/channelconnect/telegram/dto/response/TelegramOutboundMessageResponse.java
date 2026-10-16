package com.svi.channelconnect.telegram.dto.response;

public record TelegramOutboundMessageResponse(String messageId, String status, boolean replay, String providerMessageId) {
	public TelegramOutboundMessageResponse asReplay() {
		return new TelegramOutboundMessageResponse(messageId, status, true, providerMessageId);
	}
}
