package com.svi.channelconnect.sms.model;

public record SmsInbound(String id, String accountId, long receiveId, String sender, String content,
		long receivedAt, String status, int attempts) {
	@Override public String toString() { return "SmsInbound[id=" + id + ", status=" + status + ", payload=<redacted>]"; }
}
