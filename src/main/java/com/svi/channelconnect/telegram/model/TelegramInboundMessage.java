package com.svi.channelconnect.telegram.model;

public record TelegramInboundMessage(
		long updateId,
		long chatId,
		long messageId,
		Long messageThreadId,
		String text
) {
	public String conversationId() {
		return "telegram:" + chatId + (messageThreadId == null ? "" : ":" + messageThreadId);
	}

	@Override
	public String toString() {
		return "TelegramInboundMessage[updateId=" + updateId + ", chatId=<redacted>"
				+ ", messageId=" + messageId + ", messageThreadId=" + messageThreadId + ", text=<redacted>]";
	}
}
