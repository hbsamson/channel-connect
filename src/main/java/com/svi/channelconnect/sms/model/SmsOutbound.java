package com.svi.channelconnect.sms.model;

public record SmsOutbound(String id, String accountId, String recipient, String content, long sessionId,
		long createdAt, String status, String requestKey, String dealerMessageId) {
	@Override public String toString() { return "SmsOutbound[id=" + id + ", status=" + status + ", payload=<redacted>]"; }
}
