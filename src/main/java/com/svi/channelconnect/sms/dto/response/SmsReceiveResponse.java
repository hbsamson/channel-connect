package com.svi.channelconnect.sms.dto.response;

import java.util.List;

public record SmsReceiveResponse(List<Message> messages, long nextCursor) {
	public SmsReceiveResponse {
		messages = List.copyOf(messages);
	}

	public record Message(long cursor, String messageId, long receiveId, String sender, String content,
			long receivedAt, String status) {
		@Override public String toString() { return "ReceivedSms[messageId=" + messageId + ", payload=<redacted>]"; }
	}
}
