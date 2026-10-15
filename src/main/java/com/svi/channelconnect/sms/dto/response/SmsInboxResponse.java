package com.svi.channelconnect.sms.dto.response;

import java.util.List;

public record SmsInboxResponse(List<Message> messages, long nextCursor, boolean hasMore) {
	public SmsInboxResponse {
		messages = List.copyOf(messages);
	}

	public record Message(long cursor, String messageId, String accountId, long receiveId, String sender,
			String content, long receivedAt, String status) {
		@Override public String toString() { return "SmsInboxMessage[messageId=" + messageId + ", payload=<redacted>]"; }
	}
}
