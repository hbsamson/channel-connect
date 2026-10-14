package com.svi.channelconnect.common.dto;

public record OutgoingMessageDTO(String recipientId, String content, String accountId, String requestId, String dealerMessageId) {
	public OutgoingMessageDTO(String recipientId, String content) { this(recipientId, content, null, null, null); }
	@Override public String toString() { return "OutgoingMessageDTO[payload=<redacted>]"; }
}
